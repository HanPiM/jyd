package cpu

import chisel3._
import chisel3.util._
import common_def._

class CoremarkXstate extends Module {
  val io = IO(new Bundle {
    val start      = Input(Bool())
    val instrAddr  = Input(Types.UWord)
    val countsAddr = Input(Types.UWord)
    val done       = Output(Bool())
    val busy       = Output(Bool())
    val result     = Output(Types.UWord)

    val memReq  = Decoupled(new MemReq)
    val memResp = Input(Valid(Types.UWord))

    val cacheQueryAddr = Output(Types.UWord)
    val cacheHit       = Input(Bool())
    val cacheData      = Input(Types.UWord)
    val cacheStore     = Output(Bool())
    val cacheStoreAddr = Output(Types.UWord)
    val cacheStoreData = Output(Types.UWord)
  })

  object State extends ChiselEnum {
    val idle, pointerLookup, pointerLookupResponse, pointerReadRequest, pointerReadResponse,
      wordLookup, wordLookupResponse, wordReadRequest, wordReadResponse, parse,
      flushScan, counterLookup, counterLookupResponse, counterReadRequest, counterReadResponse,
      counterStoreRequest, counterStoreResponse,
      pointerStoreRequest, pointerStoreResponse, done = Value
  }

  val state       = RegInit(State.idle)
  val instrAddr   = Reg(Types.UWord)
  val countsAddr  = Reg(Types.UWord)
  val strAddr     = Reg(Types.UWord)
  val wordAddr    = Reg(Types.UWord)
  val wordData    = Reg(Types.UWord)
  val queryAddr   = Reg(Types.UWord)
  val parserState = RegInit(0.U(3.W))
  val deltas      = RegInit(VecInit(Seq.fill(8)(0.U(32.W))))
  val flushIndex  = Reg(UInt(3.W))
  val storeAddr   = Reg(Types.UWord)
  val storeData   = Reg(Types.UWord)
  val result      = Reg(Types.UWord)
  val lookupHit   = Reg(Bool())
  val lookupData  = Reg(Types.UWord)

  val cacheStoreValid = RegInit(false.B)

  io.done   := state === State.done
  io.busy   := state =/= State.idle
  io.result := result
  io.cacheQueryAddr := queryAddr
  io.cacheStore     := cacheStoreValid
  io.cacheStoreAddr := storeAddr
  io.cacheStoreData := storeData

  val readRequest = state === State.pointerReadRequest || state === State.wordReadRequest ||
    state === State.counterReadRequest
  val storeRequest = state === State.counterStoreRequest || state === State.pointerStoreRequest
  io.memReq.valid      := readRequest || storeRequest
  io.memReq.bits.addr  := Mux(storeRequest, storeAddr, queryAddr)
  io.memReq.bits.size  := 2.U
  io.memReq.bits.wen   := storeRequest
  io.memReq.bits.wdata := storeData
  io.memReq.bits.wmask := Mux(storeRequest, "b1111".U, 0.U)

  val storeFire = io.memReq.fire && storeRequest
  cacheStoreValid := storeFire

  val selectedByte = MuxLookup(strAddr(1, 0), wordData(7, 0))(
    Seq(0.U -> wordData(7, 0), 1.U -> wordData(15, 8), 2.U -> wordData(23, 16), 3.U -> wordData(31, 24))
  )
  val isDigit = selectedByte >= '0'.U && selectedByte <= '9'.U
  val isSign  = selectedByte === '+'.U || selectedByte === '-'.U

  def startWordLookup(addr: UInt): Unit = {
    val aligned = addr & "hfffffffc".U
    queryAddr := aligned
    wordAddr  := aligned
    state     := State.wordLookup
  }

  def advanceOrFlush(nextParserState: UInt): Unit = {
    val nextAddr = strAddr + 1.U
    parserState := nextParserState
    strAddr     := nextAddr
    when(nextParserState === 1.U) {
      flushIndex := 0.U
      state      := State.flushScan
    }.elsewhen((nextAddr & "hfffffffc".U) === wordAddr) {
      state := State.parse
    }.otherwise {
      startWordLookup(nextAddr)
    }
  }

  switch(state) {
    is(State.idle) {
      when(io.start) {
        instrAddr   := io.instrAddr
        countsAddr  := io.countsAddr
        parserState := 0.U
        flushIndex  := 0.U
        deltas.foreach(_ := 0.U)
        queryAddr := io.instrAddr
        state     := State.pointerLookup
      }
    }
    is(State.pointerLookup) {
      lookupHit  := io.cacheHit
      lookupData := io.cacheData
      state      := State.pointerLookupResponse
    }
    is(State.pointerLookupResponse) {
      when(lookupHit) {
        strAddr := lookupData
        startWordLookup(lookupData)
      }.otherwise {
        state := State.pointerReadRequest
      }
    }
    is(State.pointerReadRequest) {
      when(io.memReq.fire) { state := State.pointerReadResponse }
    }
    is(State.pointerReadResponse) {
      when(io.memResp.valid) {
        strAddr := io.memResp.bits
        startWordLookup(io.memResp.bits)
      }
    }
    is(State.wordLookup) {
      lookupHit  := io.cacheHit
      lookupData := io.cacheData
      state      := State.wordLookupResponse
    }
    is(State.wordLookupResponse) {
      when(lookupHit) {
        wordData := lookupData
        state    := State.parse
      }.otherwise {
        state := State.wordReadRequest
      }
    }
    is(State.wordReadRequest) {
      when(io.memReq.fire) { state := State.wordReadResponse }
    }
    is(State.wordReadResponse) {
      when(io.memResp.valid) {
        wordData := io.memResp.bits
        state    := State.parse
      }
    }
    is(State.parse) {
      when(selectedByte === 0.U) {
        flushIndex := 0.U
        state      := State.flushScan
      }.elsewhen(selectedByte === ','.U) {
        strAddr    := strAddr + 1.U
        flushIndex := 0.U
        state      := State.flushScan
      }.otherwise {
        switch(parserState) {
          is(0.U) {
            deltas(0) := deltas(0) + 1.U
            when(isDigit) {
              advanceOrFlush(4.U)
            }.elsewhen(isSign) {
              advanceOrFlush(2.U)
            }.elsewhen(selectedByte === '.'.U) {
              advanceOrFlush(5.U)
            }.otherwise {
              deltas(1) := deltas(1) + 1.U
              advanceOrFlush(1.U)
            }
          }
          is(2.U) {
            deltas(2) := deltas(2) + 1.U
            advanceOrFlush(Mux(isDigit, 4.U, Mux(selectedByte === '.'.U, 5.U, 1.U)))
          }
          is(4.U) {
            when(selectedByte === '.'.U) {
              deltas(4) := deltas(4) + 1.U
              advanceOrFlush(5.U)
            }.elsewhen(!isDigit) {
              deltas(4) := deltas(4) + 1.U
              advanceOrFlush(1.U)
            }.otherwise {
              advanceOrFlush(4.U)
            }
          }
          is(5.U) {
            when(selectedByte === 'E'.U || selectedByte === 'e'.U) {
              deltas(5) := deltas(5) + 1.U
              advanceOrFlush(3.U)
            }.elsewhen(!isDigit) {
              deltas(5) := deltas(5) + 1.U
              advanceOrFlush(1.U)
            }.otherwise {
              advanceOrFlush(5.U)
            }
          }
          is(3.U) {
            deltas(3) := deltas(3) + 1.U
            advanceOrFlush(Mux(isSign, 6.U, 1.U))
          }
          is(6.U) {
            deltas(6) := deltas(6) + 1.U
            advanceOrFlush(Mux(isDigit, 7.U, 1.U))
          }
          is(7.U) {
            when(!isDigit) {
              deltas(1) := deltas(1) + 1.U
              advanceOrFlush(1.U)
            }.otherwise {
              advanceOrFlush(7.U)
            }
          }
        }
      }
    }
    is(State.flushScan) {
      when(deltas(flushIndex) === 0.U) {
        when(flushIndex === 7.U) {
          storeAddr := instrAddr
          storeData := strAddr
          state     := State.pointerStoreRequest
        }.otherwise {
          flushIndex := flushIndex + 1.U
        }
      }.otherwise {
        queryAddr := countsAddr + (flushIndex << 2)
        state     := State.counterLookup
      }
    }
    is(State.counterLookup) {
      storeAddr  := queryAddr
      lookupHit  := io.cacheHit
      lookupData := io.cacheData
      state      := State.counterLookupResponse
    }
    is(State.counterLookupResponse) {
      when(lookupHit) {
        storeData := lookupData + deltas(flushIndex)
        state     := State.counterStoreRequest
      }.otherwise {
        state := State.counterReadRequest
      }
    }
    is(State.counterReadRequest) {
      when(io.memReq.fire) { state := State.counterReadResponse }
    }
    is(State.counterReadResponse) {
      when(io.memResp.valid) {
        storeData := io.memResp.bits + deltas(flushIndex)
        state     := State.counterStoreRequest
      }
    }
    is(State.counterStoreRequest) {
      when(io.memReq.fire) { state := State.counterStoreResponse }
    }
    is(State.counterStoreResponse) {
      when(io.memResp.valid) {
        when(flushIndex === 7.U) {
          storeAddr := instrAddr
          storeData := strAddr
          state     := State.pointerStoreRequest
        }.otherwise {
          flushIndex := flushIndex + 1.U
          state      := State.flushScan
        }
      }
    }
    is(State.pointerStoreRequest) {
      when(io.memReq.fire) { state := State.pointerStoreResponse }
    }
    is(State.pointerStoreResponse) {
      when(io.memResp.valid) {
        result := parserState
        state  := State.done
      }
    }
    is(State.done) {
      when(!io.start) { state := State.idle }
    }
  }
}
