/*
 * Copyright 2011 Ytai Ben-Tsvi. All rights reserved.
 *
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL ARSHAN POURSOHI OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied.
 */
package ioio.lib.impl

import ioio.lib.api.DigitalInput
import ioio.lib.api.DigitalOutput
import ioio.lib.api.SpiMaster
import ioio.lib.api.TwiMaster
import ioio.lib.api.Uart.Parity
import ioio.lib.api.Uart.StopBits
import ioio.lib.spi.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

internal class IOIOProtocol(private val in_: InputStream, private val out_: OutputStream, private val handler_: IncomingHandler) {
    private val thread_ = IncomingThread()
    private var batchCounter_ = 0

    @Throws(IOException::class)
    private fun writeByte(b: Int) {
        assert(b >= 0 && b < 256)
        // Log.v(TAG, "sending: 0x" + Integer.toHexString(b));
        out_.write(b)
    }

    @Throws(IOException::class)
    private fun writeBytes(buf: ByteArray?, offset: Int, size: Int) {
        var offset = offset
        var size = size
        while (size-- > 0) {
            writeByte(buf!![offset++].toInt() and 0xFF)
        }
    }

    @Synchronized
    fun beginBatch() {
        ++batchCounter_
    }

    @Synchronized
    @Throws(IOException::class)
    fun endBatch() {
        if (--batchCounter_ == 0) {
            out_.flush()
        }
    }

    @Throws(IOException::class)
    private fun writeTwoBytes(i: Int) {
        writeByte(i and 0xFF)
        writeByte(i shr 8)
    }

    @Throws(IOException::class)
    private fun writeThreeBytes(i: Int) {
        writeByte(i and 0xFF)
        writeByte(i shr 8 and 0xFF)
        writeByte(i shr 16 and 0xFF)
    }

    @Synchronized
    @Throws(IOException::class)
    fun sync() {
        beginBatch()
        writeByte(SYNC)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun hardReset() {
        beginBatch()
        writeByte(HARD_RESET)
        writeByte('I'.toInt())
        writeByte('O'.toInt())
        writeByte('I'.toInt())
        writeByte('O'.toInt())
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun softReset() {
        beginBatch()
        writeByte(SOFT_RESET)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun softClose() {
        beginBatch()
        writeByte(SOFT_CLOSE)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun checkInterface(interfaceId: ByteArray) {
        require(interfaceId.size == 8) { "interface ID must be exactly 8 bytes long" }
        beginBatch()
        writeByte(CHECK_INTERFACE)
        for (i in 0..7) {
            writeByte(interfaceId[i].toInt())
        }
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setDigitalOutLevel(pin: Int, level: Boolean) {
        beginBatch()
        writeByte(SET_DIGITAL_OUT_LEVEL)
        writeByte(pin shl 2 or if (level) 1 else 0)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinPwm(pin: Int, pwmNum: Int, enable: Boolean) {
        beginBatch()
        writeByte(SET_PIN_PWM)
        writeByte(pin and 0x3F)
        writeByte((if (enable) 0x80 else 0x00) or (pwmNum and 0x0F))
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPwmDutyCycle(pwmNum: Int, dutyCycle: Int, fraction: Int) {
        beginBatch()
        writeByte(SET_PWM_DUTY_CYCLE)
        writeByte(pwmNum shl 2 or fraction)
        writeTwoBytes(dutyCycle)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPwmPeriod(pwmNum: Int, period: Int, scale: PwmScale) {
        beginBatch()
        writeByte(SET_PWM_PERIOD)
        writeByte(scale.encoding and 0x02 shl 6 or (pwmNum shl 1) or (scale.encoding and 0x01))
        writeTwoBytes(period)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinIncap(pin: Int, incapNum: Int, enable: Boolean) {
        beginBatch()
        writeByte(SET_PIN_INCAP)
        writeByte(pin)
        writeByte(incapNum or if (enable) 0x80 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun incapClose(incapNum: Int, double_prec: Boolean) {
        beginBatch()
        writeByte(INCAP_CONFIGURE)
        writeByte(incapNum)
        writeByte(if (double_prec) 0x80 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun incapConfigure(incapNum: Int, double_prec: Boolean, mode: Int, clock: Int) {
        beginBatch()
        writeByte(INCAP_CONFIGURE)
        writeByte(incapNum)
        writeByte((if (double_prec) 0x80 else 0x00) or (mode shl 3) or clock)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun i2cWriteRead(i2cNum: Int, tenBitAddr: Boolean, address: Int,
                     writeSize: Int, readSize: Int, writeData: ByteArray) {
        beginBatch()
        writeByte(I2C_WRITE_READ)
        writeByte(address shr 8 shl 6 or (if (tenBitAddr) 0x20 else 0x00) or i2cNum)
        writeByte(address and 0xFF)
        writeByte(writeSize)
        writeByte(readSize)
        for (i in 0 until writeSize) {
            writeByte(writeData[i].toInt() and 0xFF)
        }
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinDigitalOut(pin: Int, value: Boolean, mode: DigitalOutput.Spec.Mode) {
        beginBatch()
        writeByte(SET_PIN_DIGITAL_OUT)
        writeByte(pin shl 2 or (if (mode === DigitalOutput.Spec.Mode.OPEN_DRAIN) 0x01 else 0x00)
                or if (value) 0x02 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinDigitalIn(pin: Int, mode: DigitalInput.Spec.Mode) {
        var pull = 0
        if (mode === DigitalInput.Spec.Mode.PULL_UP) {
            pull = 1
        } else if (mode === DigitalInput.Spec.Mode.PULL_DOWN) {
            pull = 2
        }
        beginBatch()
        writeByte(SET_PIN_DIGITAL_IN)
        writeByte(pin shl 2 or pull)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setChangeNotify(pin: Int, changeNotify: Boolean) {
        beginBatch()
        writeByte(SET_CHANGE_NOTIFY)
        writeByte(pin shl 2 or if (changeNotify) 0x01 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun registerPeriodicDigitalSampling(pin: Int, freqScale: Int) {
        // TODO: implement
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinAnalogIn(pin: Int) {
        beginBatch()
        writeByte(SET_PIN_ANALOG_IN)
        writeByte(pin)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setAnalogInSampling(pin: Int, enable: Boolean) {
        beginBatch()
        writeByte(SET_ANALOG_IN_SAMPLING)
        writeByte((if (enable) 0x80 else 0x00) or (pin and 0x3F))
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun uartData(uartNum: Int, numBytes: Int, data: ByteArray) {
        require(numBytes <= 64) { "A maximum of 64 bytes can be sent in one uartData message. Got: $numBytes" }
        beginBatch()
        writeByte(UART_DATA)
        writeByte(numBytes - 1 or uartNum shl 6)
        for (i in 0 until numBytes) {
            writeByte(data[i].toInt() and 0xFF)
        }
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun uartConfigure(uartNum: Int, rate: Int, speed4x: Boolean,
                      stopbits: StopBits, parity: Parity) {
        val parbits = if (parity === Parity.EVEN) 1 else if (parity === Parity.ODD) 2 else 0
        beginBatch()
        writeByte(UART_CONFIG)
        writeByte(uartNum shl 6 or (if (speed4x) 0x08 else 0x00)
                or (if (stopbits === StopBits.TWO) 0x04 else 0x00) or parbits)
        writeTwoBytes(rate)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun uartClose(uartNum: Int) {
        beginBatch()
        writeByte(UART_CONFIG)
        writeByte(uartNum shl 6)
        writeTwoBytes(0)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinUart(pin: Int, uartNum: Int, tx: Boolean, enable: Boolean) {
        beginBatch()
        writeByte(SET_PIN_UART)
        writeByte(pin)
        writeByte((if (enable) 0x80 else 0x00) or (if (tx) 0x40 else 0x00) or uartNum)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun spiConfigureMaster(spiNum: Int, config: SpiMaster.Config) {
        beginBatch()
        writeByte(SPI_CONFIGURE_MASTER)
        writeByte(spiNum shl 5 or SCALE_DIV[config.rate.ordinal])
        writeByte((if (config.sampleOnTrailing) 0x00 else 0x02) or if (config.invertClk) 0x01 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun spiClose(spiNum: Int) {
        beginBatch()
        writeByte(SPI_CONFIGURE_MASTER)
        writeByte(spiNum shl 5)
        writeByte(0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinSpi(pin: Int, mode: Int, enable: Boolean, spiNum: Int) {
        beginBatch()
        writeByte(SET_PIN_SPI)
        writeByte(pin)
        writeByte(1 shl 4 or (mode shl 2) or spiNum)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun spiMasterRequest(spiNum: Int, ssPin: Int, data: ByteArray, dataBytes: Int,
                         totalBytes: Int, responseBytes: Int) {
        val dataNeqTotal = dataBytes != totalBytes
        val resNeqTotal = responseBytes != totalBytes
        beginBatch()
        writeByte(SPI_MASTER_REQUEST)
        writeByte(spiNum shl 6 or ssPin)
        writeByte((if (dataNeqTotal) 0x80 else 0x00) or (if (resNeqTotal) 0x40 else 0x00) or totalBytes - 1)
        if (dataNeqTotal) {
            writeByte(dataBytes)
        }
        if (resNeqTotal) {
            writeByte(responseBytes)
        }
        for (i in 0 until dataBytes) {
            writeByte(data[i].toInt() and 0xFF)
        }
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun i2cConfigureMaster(i2cNum: Int, rate: TwiMaster.Rate, smbusLevels: Boolean) {
        val rateBits = if (rate === TwiMaster.Rate.RATE_1MHz) 3 else if (rate === TwiMaster.Rate.RATE_400KHz) 2 else 1
        beginBatch()
        writeByte(I2C_CONFIGURE_MASTER)
        writeByte((if (smbusLevels) 0x80 else 0) or (rateBits shl 5) or i2cNum)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun i2cClose(i2cNum: Int) {
        beginBatch()
        writeByte(I2C_CONFIGURE_MASTER)
        writeByte(i2cNum)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspOpen() {
        beginBatch()
        writeByte(ICSP_CONFIG)
        writeByte(0x01)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspClose() {
        beginBatch()
        writeByte(ICSP_CONFIG)
        writeByte(0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspEnter() {
        beginBatch()
        writeByte(ICSP_PROG_ENTER)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspExit() {
        beginBatch()
        writeByte(ICSP_PROG_EXIT)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspSix(instruction: Int) {
        beginBatch()
        writeByte(ICSP_SIX)
        writeThreeBytes(instruction)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun icspRegout() {
        beginBatch()
        writeByte(ICSP_REGOUT)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setPinCapSense(pinNum: Int) {
        beginBatch()
        writeByte(SET_PIN_CAPSENSE)
        writeByte(pinNum and 0x3F)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun setCapSenseSampling(pinNum: Int, enable: Boolean) {
        beginBatch()
        writeByte(SET_CAPSENSE_SAMPLING)
        writeByte(pinNum and 0x3F or if (enable) 0x80 else 0x00)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerOpen(config: ByteArray?, size: Int) {
        assert(config != null)
        assert(size >= 0 && size <= 68)
        beginBatch()
        writeByte(SEQUENCER_CONFIGURE)
        writeByte(size)
        writeBytes(config, 0, size)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerClose() {
        beginBatch()
        writeByte(SEQUENCER_CONFIGURE)
        writeByte(0)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerPush(duration: Int, cue: ByteArray?, size: Int) {
        assert(cue != null)
        assert(size >= 0 && size <= 68)
        assert(duration < 1 shl 16)
        beginBatch()
        writeByte(SEQUENCER_PUSH)
        writeTwoBytes(duration)
        writeBytes(cue, 0, size)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerStop() {
        beginBatch()
        writeByte(SEQUENCER_CONTROL)
        writeByte(0)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerStart() {
        beginBatch()
        writeByte(SEQUENCER_CONTROL)
        writeByte(1)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerPause() {
        beginBatch()
        writeByte(SEQUENCER_CONTROL)
        writeByte(2)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerManualStart(cue: ByteArray?, size: Int) {
        beginBatch()
        writeByte(SEQUENCER_CONTROL)
        writeByte(3)
        writeBytes(cue, 0, size)
        endBatch()
    }

    @Synchronized
    @Throws(IOException::class)
    fun sequencerManualStop() {
        beginBatch()
        writeByte(SEQUENCER_CONTROL)
        writeByte(4)
        endBatch()
    }

    internal enum class PwmScale(val scale: Int, val encoding: Int) {
        SCALE_1X(1, 0), SCALE_8X(8, 3), SCALE_64X(64, 2), SCALE_256X(256, 1);
    }

    internal enum class SequencerEvent {
        PAUSED, STALLED, OPENED, NEXT_CUE, STOPPED, CLOSED
    }

    interface IncomingHandler {
        fun handleEstablishConnection(hardwareId: ByteArray?, bootloaderId: ByteArray?,
                                      firmwareId: ByteArray?)

        fun handleConnectionLost()
        fun handleSoftReset()
        fun handleCheckInterfaceResponse(supported: Boolean)
        fun handleSetChangeNotify(pin: Int, changeNotify: Boolean)
        fun handleReportDigitalInStatus(pin: Int, level: Boolean)
        fun handleRegisterPeriodicDigitalSampling(pin: Int, freqScale: Int)
        fun handleReportPeriodicDigitalInStatus(frameNum: Int, values: BooleanArray?)
        fun handleAnalogPinStatus(pin: Int, open: Boolean)
        fun handleReportAnalogInStatus(pins: List<Int>?, values: List<Int>?)
        fun handleUartOpen(uartNum: Int)
        fun handleUartClose(uartNum: Int)
        fun handleUartData(uartNum: Int, numBytes: Int, data: ByteArray?)
        fun handleUartReportTxStatus(uartNum: Int, bytesRemaining: Int)
        fun handleSpiOpen(spiNum: Int)
        fun handleSpiClose(spiNum: Int)
        fun handleSpiData(spiNum: Int, ssPin: Int, data: ByteArray?, dataBytes: Int)
        fun handleSpiReportTxStatus(spiNum: Int, bytesRemaining: Int)
        fun handleI2cOpen(i2cNum: Int)
        fun handleI2cClose(i2cNum: Int)
        fun handleI2cResult(i2cNum: Int, size: Int, data: ByteArray?)
        fun handleI2cReportTxStatus(spiNum: Int, bytesRemaining: Int)
        fun handleIcspOpen()
        fun handleIcspClose()
        fun handleIcspReportRxStatus(bytesRemaining: Int)
        fun handleIcspResult(size: Int, data: ByteArray?)
        fun handleIncapReport(incapNum: Int, size: Int, data: ByteArray?)
        fun handleIncapClose(incapNum: Int)
        fun handleIncapOpen(incapNum: Int)
        fun handleCapSenseReport(pinNum: Int, value: Int)
        fun handleSetCapSenseSampling(pinNum: Int, enable: Boolean)
        fun handleSequencerEvent(event: SequencerEvent?, arg: Int)
        fun handleSync()
    }

    internal class ProtocolError : Exception {
        constructor(msg: String?) : super(msg) {}
        constructor(e: Exception?) : super(e) {}

        companion object {
            private const val serialVersionUID = -6973476719285599189L
        }
    }

    internal inner class IncomingThread : Thread() {
        private val analogPinValues_: MutableList<Int> = ArrayList()
        private var analogFramePins_: MutableList<Int> = ArrayList()
        private var newFramePins_: MutableList<Int> = ArrayList()
        private val removedPins_: MutableSet<Int> = HashSet()
        private val addedPins_: MutableSet<Int> = HashSet()
        private fun calculateAnalogFrameDelta() {
            removedPins_.clear()
            removedPins_.addAll(analogFramePins_)
            addedPins_.clear()
            addedPins_.addAll(newFramePins_)
            // Remove the intersection from both.
            val it = removedPins_.iterator()
            while (it.hasNext()) {
                val current = it.next()
                if (addedPins_.contains(current)) {
                    it.remove()
                    addedPins_.remove(current)
                }
            }
            // swap
            val temp = analogFramePins_
            analogFramePins_ = newFramePins_
            newFramePins_ = temp
        }

        @Throws(IOException::class)
        private fun readByte(): Int {
            return try {
                val b = in_.read()
                if (b < 0) {
                    throw IOException("Unexpected stream closure")
                }

                // Log.v(TAG, "received: 0x" + Integer.toHexString(b));
                b
            } catch (e: IOException) {
                Log.i(TAG, "IOIO disconnected")
                throw e
            }
        }

        @Throws(IOException::class)
        private fun readBytes(size: Int, buffer: ByteArray) {
            for (i in 0 until size) {
                buffer[i] = readByte().toByte()
            }
        }

        override fun run() {
            super.run()
            priority = MAX_PRIORITY
            var arg1: Int
            var arg2: Int
            var numPins: Int
            var size: Int
            val data = ByteArray(256)
            try {
                while (true) {
                    when (readByte().also { arg1 = it }) {
                        ESTABLISH_CONNECTION -> {
                            if (readByte() != 'I'.toInt() || readByte() != 'O'.toInt() || readByte() != 'I'.toInt() || readByte() != 'O'.toInt()) {
                                throw IOException("Bad establish connection magic")
                            }
                            val hardwareId = ByteArray(8)
                            val bootloaderId = ByteArray(8)
                            val firmwareId = ByteArray(8)
                            readBytes(8, hardwareId)
                            readBytes(8, bootloaderId)
                            readBytes(8, firmwareId)
                            handler_.handleEstablishConnection(hardwareId, bootloaderId, firmwareId)
                        }
                        SOFT_RESET -> {
                            analogFramePins_.clear()
                            handler_.handleSoftReset()
                        }
                        REPORT_DIGITAL_IN_STATUS -> {
                            arg1 = readByte()
                            handler_.handleReportDigitalInStatus(arg1 shr 2, arg1 and 0x01 == 1)
                        }
                        SET_CHANGE_NOTIFY -> {
                            arg1 = readByte()
                            handler_.handleSetChangeNotify(arg1 shr 2, arg1 and 0x01 == 1)
                        }
                        REGISTER_PERIODIC_DIGITAL_SAMPLING -> {
                        }
                        REPORT_PERIODIC_DIGITAL_IN_STATUS -> {
                        }
                        REPORT_ANALOG_IN_FORMAT -> {
                            numPins = readByte()
                            newFramePins_.clear()
                            var i = 0
                            while (i < numPins) {
                                newFramePins_.add(readByte())
                                ++i
                            }
                            calculateAnalogFrameDelta()
                            removedPins_.forEach {
                                handler_.handleAnalogPinStatus(it, false)
                            }
                            addedPins_.forEach {
                                handler_.handleAnalogPinStatus(it, true)
                            }
                        }
                        REPORT_ANALOG_IN_STATUS -> {
                            numPins = analogFramePins_.size
                            var header = 0
                            analogPinValues_.clear()
                            var i = 0
                            while (i < numPins) {
                                if (i % 4 == 0) {
                                    header = readByte()
                                }
                                analogPinValues_.add(readByte() shl 2 or (header and 0x03))
                                header = header shr 2
                                ++i
                            }
                            handler_.handleReportAnalogInStatus(analogFramePins_, analogPinValues_)
                        }
                        UART_REPORT_TX_STATUS -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            handler_.handleUartReportTxStatus(arg1 and 0x03, arg1 shr 2 or (arg2 shl 6))
                        }
                        UART_DATA -> {
                            arg1 = readByte()
                            size = (arg1 and 0x3F) + 1
                            readBytes(size, data)
                            handler_.handleUartData(arg1 shr 6, size, data)
                        }
                        UART_STATUS -> {
                            arg1 = readByte()
                            if (arg1 and 0x80 != 0) {
                                handler_.handleUartOpen(arg1 and 0x03)
                            } else {
                                handler_.handleUartClose(arg1 and 0x03)
                            }
                        }
                        SPI_DATA -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            size = (arg1 and 0x3F) + 1
                            readBytes(size, data)
                            handler_.handleSpiData(arg1 shr 6, arg2 and 0x3F, data, size)
                        }
                        SPI_REPORT_TX_STATUS -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            handler_.handleSpiReportTxStatus(arg1 and 0x03, arg1 shr 2 or (arg2 shl 6))
                        }
                        SPI_STATUS -> {
                            arg1 = readByte()
                            if (arg1 and 0x80 != 0) {
                                handler_.handleSpiOpen(arg1 and 0x03)
                            } else {
                                handler_.handleSpiClose(arg1 and 0x03)
                            }
                        }
                        I2C_STATUS -> {
                            arg1 = readByte()
                            if (arg1 and 0x80 != 0) {
                                handler_.handleI2cOpen(arg1 and 0x03)
                            } else {
                                handler_.handleI2cClose(arg1 and 0x03)
                            }
                        }
                        I2C_RESULT -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            if (arg2 != 0xFF) {
                                readBytes(arg2, data)
                            }
                            handler_.handleI2cResult(arg1 and 0x03, arg2, data)
                        }
                        I2C_REPORT_TX_STATUS -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            handler_.handleI2cReportTxStatus(arg1 and 0x03, arg1 shr 2 or (arg2 shl 6))
                        }
                        CHECK_INTERFACE_RESPONSE -> {
                            arg1 = readByte()
                            handler_.handleCheckInterfaceResponse(arg1 and 0x01 == 1)
                        }
                        ICSP_REPORT_RX_STATUS -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            handler_.handleIcspReportRxStatus(arg1 or (arg2 shl 8))
                        }
                        ICSP_RESULT -> {
                            readBytes(2, data)
                            handler_.handleIcspResult(2, data)
                        }
                        ICSP_CONFIG -> {
                            arg1 = readByte()
                            if (arg1 and 0x01 == 1) {
                                handler_.handleIcspOpen()
                            } else {
                                handler_.handleIcspClose()
                            }
                        }
                        INCAP_STATUS -> {
                            arg1 = readByte()
                            if (arg1 and 0x80 != 0) {
                                handler_.handleIncapOpen(arg1 and 0x0F)
                            } else {
                                handler_.handleIncapClose(arg1 and 0x0F)
                            }
                        }
                        INCAP_REPORT -> {
                            arg1 = readByte()
                            size = arg1 shr 6
                            if (size == 0) {
                                size = 4
                            }
                            readBytes(size, data)
                            handler_.handleIncapReport(arg1 and 0x0F, size, data)
                        }
                        SOFT_CLOSE -> {
                            Log.d(TAG, "Received soft close.")
                            throw IOException("Soft close")
                        }
                        CAPSENSE_REPORT -> {
                            arg1 = readByte()
                            arg2 = readByte()
                            handler_.handleCapSenseReport(arg1 and 0x3F, arg1 shr 6 or (arg2 shl 2))
                        }
                        SET_CAPSENSE_SAMPLING -> {
                            arg1 = readByte()
                            handler_.handleSetCapSenseSampling(arg1 and 0x3F, arg1 and 0x80 != 0)
                        }
                        SEQUENCER_EVENT -> {
                            arg1 = readByte()
                            // OPEN and STOPPED events has an additional argument.
                            arg2 = if (arg1 == 2 || arg1 == 4) {
                                readByte()
                            } else {
                                0
                            }
                            try {
                                handler_.handleSequencerEvent(SequencerEvent.values()[arg1], arg2)
                            } catch (e: ArrayIndexOutOfBoundsException) {
                                throw IOException("Unexpected eveent: $arg1")
                            }
                        }
                        SYNC -> handler_.handleSync()
                        else -> throw ProtocolError("Received unexpected command: 0x"
                                + Integer.toHexString(arg1))
                    }
                }
            } catch (e: IOException) {
                // This is the proper way to close -- nothing's wrong.
            } catch (e: ProtocolError) {
                // This indicates invalid data coming in -- report the error.
                Log.e(TAG, "Protocol error: ", e)
            } catch (e: Exception) {
                // This also probably indicates invalid data coming in, which has been detected by
                // the command handler -- report the error.
                Log.e(TAG, "Protocol error: ", ProtocolError(e))
            } finally {
                try {
                    in_.close()
                } catch (e: IOException) {
                }
                handler_.handleConnectionLost()
            }
        }
    }

    companion object {
        const val HARD_RESET = 0x00
        const val ESTABLISH_CONNECTION = 0x00
        const val SOFT_RESET = 0x01
        const val CHECK_INTERFACE = 0x02
        const val CHECK_INTERFACE_RESPONSE = 0x02
        const val SET_PIN_DIGITAL_OUT = 0x03
        const val SET_DIGITAL_OUT_LEVEL = 0x04
        const val REPORT_DIGITAL_IN_STATUS = 0x04
        const val SET_PIN_DIGITAL_IN = 0x05
        const val REPORT_PERIODIC_DIGITAL_IN_STATUS = 0x05
        const val SET_CHANGE_NOTIFY = 0x06
        const val REGISTER_PERIODIC_DIGITAL_SAMPLING = 0x07
        const val SET_PIN_PWM = 0x08
        const val SET_PWM_DUTY_CYCLE = 0x09
        const val SET_PWM_PERIOD = 0x0A
        const val SET_PIN_ANALOG_IN = 0x0B
        const val REPORT_ANALOG_IN_STATUS = 0x0B
        const val SET_ANALOG_IN_SAMPLING = 0x0C
        const val REPORT_ANALOG_IN_FORMAT = 0x0C
        const val UART_CONFIG = 0x0D
        const val UART_STATUS = 0x0D
        const val UART_DATA = 0x0E
        const val SET_PIN_UART = 0x0F
        const val UART_REPORT_TX_STATUS = 0x0F
        const val SPI_CONFIGURE_MASTER = 0x10
        const val SPI_STATUS = 0x10
        const val SPI_MASTER_REQUEST = 0x11
        const val SPI_DATA = 0x11
        const val SET_PIN_SPI = 0x12
        const val SPI_REPORT_TX_STATUS = 0x12
        const val I2C_CONFIGURE_MASTER = 0x13
        const val I2C_STATUS = 0x13
        const val I2C_WRITE_READ = 0x14
        const val I2C_RESULT = 0x14
        const val I2C_REPORT_TX_STATUS = 0x15
        const val ICSP_SIX = 0x16
        const val ICSP_REPORT_RX_STATUS = 0x16
        const val ICSP_REGOUT = 0x17
        const val ICSP_RESULT = 0x17
        const val ICSP_PROG_ENTER = 0x18
        const val ICSP_PROG_EXIT = 0x19
        const val ICSP_CONFIG = 0x1A
        const val INCAP_CONFIGURE = 0x1B
        const val INCAP_STATUS = 0x1B
        const val SET_PIN_INCAP = 0x1C
        const val INCAP_REPORT = 0x1C
        const val SOFT_CLOSE = 0x1D
        const val SET_PIN_CAPSENSE = 0x1E
        const val CAPSENSE_REPORT = 0x1E
        const val SET_CAPSENSE_SAMPLING = 0x1F
        const val SEQUENCER_CONFIGURE = 0x20
        const val SEQUENCER_EVENT = 0x20
        const val SEQUENCER_PUSH = 0x21
        const val SEQUENCER_CONTROL = 0x22
        const val SYNC = 0x23
        val SCALE_DIV = intArrayOf(
                0x1F,  // 31.25
                0x1E,  // 35.714
                0x1D,  // 41.667
                0x1C,  // 50
                0x1B,  // 62.5
                0x1A,  // 83.333
                0x17,  // 125
                0x16,  // 142.857
                0x15,  // 166.667
                0x14,  // 200
                0x13,  // 250
                0x12,  // 333.333
                0x0F,  // 500
                0x0E,  // 571.429
                0x0D,  // 666.667
                0x0C,  // 800
                0x0B,  // 1000
                0x0A,  // 1333.333
                0x07,  // 2000
                0x06,  // 2285.714
                0x05,  // 2666.667
                0x04,  // 3200
                0x03,  // 4000
                0x02,  // 5333.333
                0x01 // 8000
        )
        private const val TAG = "IOIOProtocol"
    }

    init {
        thread_.start()
    }
}