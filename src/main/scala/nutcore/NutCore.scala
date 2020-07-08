package nutcore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import bus.simplebus._
import bus.axi4._
import utils._
import top.Settings

trait HasNutCoreParameter {
  val XLEN = if (Settings.get("IsRV32")) 32 else 64
  val HasMExtension = true
  val HasCExtension = true
  val HasDiv = true
  val HasIcache = Settings.get("HasIcache")
  val HasDcache = Settings.get("HasDcache")
  val HasITLB = Settings.get("HasITLB")
  val HasDTLB = Settings.get("HasDTLB")
  val AddrBits = 64 // AddrBits is used in some cases
  val VAddrBits = if (Settings.get("IsRV32")) 32 else 39 // VAddrBits is Virtual Memory addr bits
  val PAddrBits = 32 // PAddrBits is Phyical Memory addr bits
  val AddrBytes = AddrBits / 8 // unused
  val DataBits = XLEN
  val DataBytes = DataBits / 8
  val EnableMultiCyclePredictor = false
  val EnableOutOfOrderMemAccess = false
  val EnableMultiIssue = Settings.get("EnableMultiIssue")
  val EnableSuperScalarExec = Settings.get("EnableSuperScalarExec")
  val EnableOutOfOrderExec = Settings.get("EnableOutOfOrderExec")
  val EnableVirtualMemory = if (Settings.get("HasDTLB") && Settings.get("HasITLB")) true else false
  val EnablePerfCnt = false
}

trait HasNutCoreConst extends HasNutCoreParameter {
  val CacheReadWidth = 8
  val ICacheUserBundleWidth = VAddrBits*2 + 9
  val DCacheUserBundleWidth = 16
  val IndependentBru = if (Settings.get("EnableOutOfOrderExec")) true else false
}

abstract class NutCoreModule extends Module with HasNutCoreParameter with HasNutCoreConst with HasExceptionNO with HasBackendConst
abstract class NutCoreBundle extends Bundle with HasNutCoreParameter with HasNutCoreConst with HasBackendConst

case class NutCoreConfig (
  FPGAPlatform: Boolean = true,
  EnableDebug: Boolean = Settings.get("EnableDebug")
)

object AddressSpace extends HasNutCoreParameter {
  // (start, size)
  // address out of MMIO will be considered as DRAM
  def mmio = List(
    (0x30000000L, 0x10000000L),  // internal devices, such as CLINT and PLIC
    (Settings.getLong("MMIOBase"), Settings.getLong("MMIOSize")) // external devices
  )

  def isMMIO(addr: UInt) = mmio.map(range => {
    require(isPow2(range._2))
    val bits = log2Up(range._2)
    (addr ^ range._1.U)(PAddrBits-1, bits) === 0.U
  }).reduce(_ || _)
}

class NutCore(implicit val p: NutCoreConfig) extends NutCoreModule {
  val io = IO(new Bundle {
    val imem = new SimpleBusC
    val dmem = new SimpleBusC
    val mmio = new SimpleBusUC
    val frontend = Flipped(new SimpleBusUC())
  })

  def pipelineConnect2[T <: Data](left: DecoupledIO[T], right: DecoupledIO[T],
    isFlush: Bool, entries: Int = 4, pipe: Boolean = false) = {
    right <> FlushableQueue(left, isFlush,  entries = entries, pipe = pipe)
  }

  // Frontend

  val ifu  = Module(new IFU)
  val ibf = Module(new IBF)
  val idu  = Module(new IDU)

  pipelineConnect2(ifu.io.out, ibf.io.in, ifu.io.flushVec(0))
  PipelineVector2Connect(new CtrlFlowIO, ibf.io.out(0), ibf.io.out(1), idu.io.in(0), idu.io.in(1), ifu.io.flushVec(1), if (EnableOutOfOrderExec) 8 else 4)
  ibf.io.flush := ifu.io.flushVec(1)
  idu.io.flush := ifu.io.flushVec(1)
  
  Debug() {
    printf("------------------------ FRONTEND: %d ------------------------\n", GTimer())
    printf("flush = %b, ifu:(%d,%d), ibf:(%d,%d), idu:(%d,%d)\n",
      ifu.io.flushVec.asUInt, ifu.io.out.valid, ifu.io.out.ready,
      ibf.io.in.valid, ibf.io.in.ready, idu.io.in(0).valid, idu.io.in(0).ready)
    when (ifu.io.out.valid) { printf("IFU: pc = 0x%x, instr = 0x%x\n", ifu.io.out.bits.pc, ifu.io.out.bits.instr)} ; 
    when (ibf.io.in.valid) { printf("IBF: pc = 0x%x, instr = 0x%x\n", ibf.io.in.bits.pc, ibf.io.in.bits.instr)}
    when (idu.io.in(0).valid) { printf("IDU1: pc = 0x%x, instr = 0x%x, pnpc = 0x%x\n", idu.io.in(0).bits.pc, idu.io.in(0).bits.instr, idu.io.in(0).bits.pnpc) }
    when (idu.io.in(1).valid) { printf("IDU2: pc = 0x%x, instr = 0x%x, pnpc = 0x%x\n", idu.io.in(1).bits.pc, idu.io.in(1).bits.instr, idu.io.in(1).bits.pnpc) }
  }

  if(EnableOutOfOrderExec){

    val mmioXbar = Module(new SimpleBusCrossbarNto1(if (HasDcache) 2 else 3))
    val backend = Module(new Backend)
    PipelineVector2Connect(new DecodeIO, idu.io.out(0), idu.io.out(1), backend.io.in(0), backend.io.in(1), ifu.io.flushVec(1), 16)
    backend.io.flush := ifu.io.flushVec(2)
    ifu.io.redirect <> backend.io.redirect

    val dmemXbar = Module(new SimpleBusAutoIDCrossbarNto1(4, userBits = if (HasDcache) DCacheUserBundleWidth else 0))

    val itlb = TLB(in = ifu.io.imem, mem = dmemXbar.io.in(2), flush = ifu.io.flushVec(0) | ifu.io.bpFlush, csrMMU = backend.io.memMMU.imem)(TLBConfig(name = "itlb", userBits = ICacheUserBundleWidth, totalEntry = 4))
    ifu.io.ipf := itlb.io.ipf
    io.imem <> Cache(in = itlb.io.out, mmio = mmioXbar.io.in.take(1), flush = Fill(2, ifu.io.flushVec(0) | ifu.io.bpFlush), empty = itlb.io.cacheEmpty)(
      CacheConfig(ro = true, name = "icache", userBits = ICacheUserBundleWidth))
    
    val dtlb = TLB(in = backend.io.dtlb, mem = dmemXbar.io.in(1), flush = ifu.io.flushVec(3), csrMMU = backend.io.memMMU.dmem)(TLBConfig(name = "dtlb", userBits = DCacheUserBundleWidth, totalEntry = 64))
    dtlb.io.out := DontCare //FIXIT
    dtlb.io.out.req.ready := true.B //FIXIT

    if(EnableVirtualMemory){
      dmemXbar.io.in(3) <> backend.io.dmem
      io.dmem <> Cache(in = dmemXbar.io.out, mmio = mmioXbar.io.in.drop(1), flush = "b00".U, empty = dtlb.io.cacheEmpty, enable = HasDcache)(
        CacheConfig(ro = false, name = "dcache", userBits = DCacheUserBundleWidth, idBits = 4))
    }else{
      dmemXbar.io.in(1) := DontCare
      dmemXbar.io.in(3) := DontCare
      dmemXbar.io.out := DontCare
      io.dmem <> Cache(in = backend.io.dmem, mmio = mmioXbar.io.in.drop(1), flush = "b00".U, empty = dtlb.io.cacheEmpty, enable = HasDcache)(
        CacheConfig(ro = false, name = "dcache", userBits = DCacheUserBundleWidth))
    }

    // Make DMA access through L1 DCache to keep coherence
    val expender = Module(new SimpleBusUCExpender(userBits = DCacheUserBundleWidth, userVal = 0.U))
    expender.io.in <> io.frontend
    dmemXbar.io.in(0) <> expender.io.out

    io.mmio <> mmioXbar.io.out

    Debug(){
      printf("------------------------ BACKEND : %d ------------------------\n", GTimer())
    }
  }else{
    val backend = Module(new Backend_seq)

    PipelineVector2Connect(new DecodeIO, idu.io.out(0), idu.io.out(1), backend.io.in(0), backend.io.in(1), ifu.io.flushVec(1), 4)

    val mmioXbar = Module(new SimpleBusCrossbarNto1(2))
    val dmemXbar = Module(new SimpleBusCrossbarNto1(4))

    val itlb = EmbeddedTLB(in = ifu.io.imem, mem = dmemXbar.io.in(1), flush = ifu.io.flushVec(0) | ifu.io.bpFlush, csrMMU = backend.io.memMMU.imem, enable = HasITLB)(TLBConfig(name = "itlb", userBits = ICacheUserBundleWidth, totalEntry = 4))
    ifu.io.ipf := itlb.io.ipf
    io.imem <> Cache(in = itlb.io.out, mmio = mmioXbar.io.in.take(1), flush = Fill(2, ifu.io.flushVec(0) | ifu.io.bpFlush), empty = itlb.io.cacheEmpty, enable = HasIcache)(CacheConfig(ro = true, name = "icache", userBits = ICacheUserBundleWidth))
    
    // dtlb
    val dtlb = EmbeddedTLB(in = backend.io.dmem, mem = dmemXbar.io.in(2), flush = false.B, csrMMU = backend.io.memMMU.dmem, enable = HasDTLB)(TLBConfig(name = "dtlb", totalEntry = 64))
    dmemXbar.io.in(0) <> dtlb.io.out
    io.dmem <> Cache(in = dmemXbar.io.out, mmio = mmioXbar.io.in.drop(1), flush = "b00".U, empty = dtlb.io.cacheEmpty, enable = HasDcache)(CacheConfig(ro = false, name = "dcache"))

    // redirect
    ifu.io.redirect <> backend.io.redirect
    backend.io.flush := ifu.io.flushVec(3,2)

    // Make DMA access through L1 DCache to keep coherence
    dmemXbar.io.in(3) <> io.frontend

    io.mmio <> mmioXbar.io.out

    Debug() {
      printf("------------------------ BACKEND : %d ------------------------\n", GTimer())
    }
  } 
}
