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

import ioio.lib.api.*
import ioio.lib.api.IOIO.VersionType
import ioio.lib.api.PulseInput.ClockRate
import ioio.lib.api.PulseInput.PulseMode
import ioio.lib.api.Sequencer.ChannelConfig
import ioio.lib.api.Uart.Parity
import ioio.lib.api.Uart.StopBits
import ioio.lib.api.exception.ConnectionLostException
import ioio.lib.api.exception.IncompatibilityException
import ioio.lib.impl.IOIOProtocol.PwmScale
import ioio.lib.impl.IncomingState.DisconnectListener
import ioio.lib.impl.ResourceManager.ResourceType
import ioio.lib.spi.Log
import java.io.IOException

class IOIOImpl(private val connection_: IOIOConnection) : IOIO, DisconnectListener {
    @JvmField
    var protocol_: IOIOProtocol? = null

    @JvmField
    var resourceManager_: ResourceManager? = null

    @JvmField
    var incomingState_ = IncomingState()

    @JvmField
    var hardware_: Board.Hardware? = null
    private var disconnect_ = false
    override var state = IOIO.State.INIT
        private set

    @Throws(ConnectionLostException::class, IncompatibilityException::class)
    override fun waitForConnect() {
        if (state === IOIO.State.CONNECTED) {
            return
        }
        if (state === IOIO.State.DEAD) {
            throw ConnectionLostException()
        }
        addDisconnectListener(this)
        Log.d(TAG, "Waiting for IOIO connection")
        try {
            try {
                Log.v(TAG, "Waiting for underlying connection")
                connection_.waitForConnect()
                synchronized(this) {
                    if (disconnect_) {
                        throw ConnectionLostException()
                    }
                    protocol_ = IOIOProtocol(connection_.inputStream, connection_.outputStream, incomingState_)
                }
            } catch (e: ConnectionLostException) {
                incomingState_.handleConnectionLost()
                throw e
            }
            Log.v(TAG, "Waiting for handshake")
            incomingState_.waitConnectionEstablished()
            initBoard()
            Log.v(TAG, "Querying for required interface ID")
            checkInterfaceVersion()
            Log.v(TAG, "Required interface ID is supported")
            state = IOIO.State.CONNECTED
            Log.i(TAG, "IOIO connection established")
        } catch (e: ConnectionLostException) {
            Log.d(TAG, "Connection lost / aborted")
            state = IOIO.State.DEAD
            throw e
        } catch (e: IncompatibilityException) {
            throw e
        } catch (e: InterruptedException) {
            Log.e(TAG, "Unexpected exception", e)
        }
    }

    @Synchronized
    override fun disconnect() {
        Log.d(TAG, "Client requested disconnect.")
        if (disconnect_) {
            return
        }
        disconnect_ = true
        try {
            if (protocol_ != null && !connection_.canClose()) {
                protocol_!!.softClose()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Soft close failed", e)
        }
        connection_.disconnect()
    }

    @Synchronized
    override fun disconnected() {
        state = IOIO.State.DEAD
        if (disconnect_) {
            return
        }
        Log.d(TAG, "Physical disconnect.")
        disconnect_ = true
        // The IOIOConnection doesn't necessarily know about the disconnect
        connection_.disconnect()
    }

    @Throws(InterruptedException::class)
    override fun waitForDisconnect() {
        incomingState_.waitDisconnect()
    }

    @Throws(IncompatibilityException::class)
    private fun initBoard() {
        if (incomingState_.board_ == null) {
            throw IncompatibilityException("Unknown board: "
                    + incomingState_.hardwareId_)
        }
        hardware_ = incomingState_.board_.hardware
        resourceManager_ = ResourceManager(hardware_)
    }

    @Throws(IncompatibilityException::class, ConnectionLostException::class, InterruptedException::class)
    private fun checkInterfaceVersion() {
        try {
            protocol_!!.checkInterface(REQUIRED_INTERFACE_ID)
        } catch (e: IOException) {
            throw ConnectionLostException(e)
        }
        if (!incomingState_.waitForInterfaceSupport()) {
            state = IOIO.State.INCOMPATIBLE
            Log.e(TAG, "Required interface ID is not supported")
            throw IncompatibilityException("IOIO firmware does not support required firmware: "
                    + String(REQUIRED_INTERFACE_ID))
        }
    }

    @Synchronized
    fun removeDisconnectListener(listener: DisconnectListener) {
        incomingState_.removeDisconnectListener(listener)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    fun addDisconnectListener(listener: DisconnectListener) {
        incomingState_.addDisconnectListener(listener)
    }

    @Synchronized
    fun closePin(pin: ResourceManager.Resource) {
        try {
            protocol_!!.setPinDigitalIn(pin.id, DigitalInput.Spec.Mode.FLOATING)
            resourceManager_!!.free(pin)
        } catch (e: IOException) {
        }
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun softReset() {
        checkState()
        try {
            protocol_!!.softReset()
        } catch (e: IOException) {
            throw ConnectionLostException(e)
        }
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun hardReset() {
        checkState()
        try {
            protocol_!!.hardReset()
        } catch (e: IOException) {
            throw ConnectionLostException(e)
        }
    }

    override fun getImplVersion(v: VersionType): String? {
        check(!(state === IOIO.State.INIT)) { "Connection has not yet been established" }
        when (v) {
            VersionType.HARDWARE_VER -> return incomingState_.hardwareId_
            VersionType.BOOTLOADER_VER -> return incomingState_.bootloaderId_
            VersionType.APP_FIRMWARE_VER -> return incomingState_.firmwareId_
            VersionType.IOIOLIB_VER -> return Version.get()
        }
        return null
    }

    @Throws(ConnectionLostException::class)
    override fun openDigitalInput(pin: Int): DigitalInput? {
        return openDigitalInput(DigitalInput.Spec(pin))
    }

    @Throws(ConnectionLostException::class)
    override fun openDigitalInput(pin: Int, mode: DigitalInput.Spec.Mode): DigitalInput? {
        return openDigitalInput(DigitalInput.Spec(pin, mode!!))
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openDigitalInput(spec: DigitalInput.Spec): DigitalInput? {
        checkState()
        val pin = ResourceManager.Resource(ResourceType.PIN, spec!!.pin)
        resourceManager_!!.alloc(pin)
        val result = DigitalInputImpl(this, pin)
        addDisconnectListener(result)
        incomingState_.addInputPinListener(spec.pin, result)
        try {
            protocol_!!.setPinDigitalIn(spec.pin, spec.mode)
            protocol_!!.setChangeNotify(spec.pin, true)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openDigitalOutput(pin: Int, mode: DigitalOutput.Spec.Mode, startValue: Boolean): DigitalOutput? {
        return openDigitalOutput(DigitalOutput.Spec(pin, mode!!), startValue)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openDigitalOutput(spec: DigitalOutput.Spec, startValue: Boolean): DigitalOutput? {
        checkState()
        val pin = ResourceManager.Resource(ResourceType.PIN, spec!!.pin)
        resourceManager_!!.alloc(pin)
        val result = DigitalOutputImpl(this, pin, startValue)
        addDisconnectListener(result)
        try {
            protocol_!!.setPinDigitalOut(spec.pin, startValue, spec.mode)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openDigitalOutput(pin: Int, startValue: Boolean): DigitalOutput? {
        return openDigitalOutput(DigitalOutput.Spec(pin), startValue)
    }

    @Throws(ConnectionLostException::class)
    override fun openDigitalOutput(pin: Int): DigitalOutput? {
        return openDigitalOutput(DigitalOutput.Spec(pin), false)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openAnalogInput(pinNum: Int): AnalogInput? {
        checkState()
        hardware_!!.checkSupportsAnalogInput(pinNum)
        val pin = ResourceManager.Resource(ResourceType.PIN, pinNum)
        resourceManager_!!.alloc(pin)
        val result = AnalogInputImpl(this, pin)
        addDisconnectListener(result)
        incomingState_.addInputPinListener(pinNum, result)
        try {
            protocol_!!.setPinAnalogIn(pinNum)
            protocol_!!.setAnalogInSampling(pinNum, true)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openCapSense(pin: Int): CapSense? {
        return openCapSense(pin, CapSense.DEFAULT_COEF)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openCapSense(pinNum: Int, filterCoef: Float): CapSense? {
        checkState()
        hardware_!!.checkSupportsCapSense(pinNum)
        val pin = ResourceManager.Resource(ResourceType.PIN, pinNum)
        resourceManager_!!.alloc(pin)
        val result = CapSenseImpl(this, pin, filterCoef)
        addDisconnectListener(result)
        incomingState_.addInputPinListener(pinNum, result)
        try {
            protocol_!!.setPinCapSense(pinNum)
            protocol_!!.setCapSenseSampling(pinNum, true)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openPwmOutput(pin: Int, freqHz: Int): PwmOutput? {
        return openPwmOutput(DigitalOutput.Spec(pin), freqHz)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openPwmOutput(spec: DigitalOutput.Spec, freqHz: Int): PwmOutput? {
        checkState()
        hardware_!!.checkSupportsPeripheralOutput(spec!!.pin)
        val pin = ResourceManager.Resource(ResourceType.PIN, spec.pin)
        val oc = ResourceManager.Resource(ResourceType.OUTCOMPARE)
        resourceManager_!!.alloc(pin, oc)
        var scale = 0
        val baseUs: Float
        var period: Int
        while (true) {
            val clk = 16000000 / PwmScale.values()[scale].scale
            period = clk / freqHz
            if (period <= 65536) {
                baseUs = 1000000.0f / clk
                break
            }
            require(++scale < PwmScale.values().size) {
                ("Frequency too low: "
                        + freqHz)
            }
        }
        val pwm = PwmImpl(this, pin, oc, period, baseUs)
        addDisconnectListener(pwm)
        try {
            protocol_!!.setPinDigitalOut(spec.pin, false, spec.mode)
            protocol_!!.setPinPwm(spec.pin, oc.id, true)
            protocol_!!.setPwmPeriod(oc.id, period - 1,
                    PwmScale.values()[scale])
        } catch (e: IOException) {
            pwm.close()
            throw ConnectionLostException(e)
        }
        return pwm
    }

    @Throws(ConnectionLostException::class)
    override fun openUart(rx: Int, tx: Int, baud: Int, parity: Parity, stopbits: StopBits): Uart? {
        return openUart(if (rx == IOIO.INVALID_PIN)
            null
        else DigitalInput.Spec(rx),
                if (tx == IOIO.INVALID_PIN)
                    null
                else DigitalOutput.Spec(tx), baud, parity, stopbits)
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openUart(rx: DigitalInput.Spec?, tx: DigitalOutput.Spec?, baud: Int, parity: Parity, stopbits: StopBits): Uart? {
        checkState()
        if (rx != null) {
            hardware_!!.checkSupportsPeripheralInput(rx.pin)
        }
        if (tx != null) {
            hardware_!!.checkSupportsPeripheralOutput(tx.pin)
        }
        val rxPin = if (rx != null) ResourceManager.Resource(ResourceType.PIN, rx.pin) else null
        val txPin = if (tx != null) ResourceManager.Resource(ResourceType.PIN, tx.pin) else null
        val uart = ResourceManager.Resource(ResourceType.UART)
        resourceManager_!!.alloc(rxPin, txPin, uart)
        val result = UartImpl(this, txPin, rxPin, uart)
        addDisconnectListener(result)
        incomingState_.addUartListener(uart.id, result)
        try {
            if (rx != null) {
                protocol_!!.setPinDigitalIn(rx.pin, rx.mode)
                protocol_!!.setPinUart(rx.pin, uart.id, false, true)
            }
            if (tx != null) {
                protocol_!!.setPinDigitalOut(tx.pin, true, tx.mode)
                protocol_!!.setPinUart(tx.pin, uart.id, true, true)
            }
            var speed4x = true
            var rate = Math.round(4000000.0f / baud) - 1
            if (rate > 65535) {
                speed4x = false
                rate = Math.round(1000000.0f / baud) - 1
            }
            protocol_!!.uartConfigure(uart.id, rate, speed4x, stopbits, parity)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openTwiMaster(twiNum: Int, rate: TwiMaster.Rate, smbus: Boolean): TwiMaster? {
        checkState()
        val twiPins = hardware_!!.twiPins()
        val twi = ResourceManager.Resource(ResourceType.TWI, twiNum)
        val pins = arrayOf(
                ResourceManager.Resource(ResourceType.PIN, twiPins[twiNum][0]),
                ResourceManager.Resource(ResourceType.PIN, twiPins[twiNum][1]))
        resourceManager_!!.alloc(twi, pins)
        val result = TwiMasterImpl(this, twi, pins)
        addDisconnectListener(result)
        incomingState_.addTwiListener(twiNum, result)
        try {
            protocol_!!.i2cConfigureMaster(twiNum, rate, smbus)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openIcspMaster(): IcspMaster? {
        checkState()
        val icspPins = hardware_!!.icspPins()
        val icsp = ResourceManager.Resource(ResourceType.ICSP)
        val pins = arrayOf(
                ResourceManager.Resource(ResourceType.PIN, icspPins[0]),
                ResourceManager.Resource(ResourceType.PIN, icspPins[1]),
                ResourceManager.Resource(ResourceType.PIN, icspPins[2]))
        resourceManager_!!.alloc(icsp, pins)
        val result = IcspMasterImpl(this, icsp, pins)
        addDisconnectListener(result)
        incomingState_.addIcspListener(result)
        try {
            protocol_!!.icspOpen()
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openSpiMaster(miso: Int, mosi: Int, clk: Int, slaveSelect: Int, rate: SpiMaster.Rate): SpiMaster? {
        return openSpiMaster(miso, mosi, clk, intArrayOf(slaveSelect), rate)
    }

    @Throws(ConnectionLostException::class)
    override fun openSpiMaster(miso: Int, mosi: Int, clk: Int, slaveSelect: IntArray, rate: SpiMaster.Rate): SpiMaster? {
        val slaveSpecs = arrayOfNulls<DigitalOutput.Spec>(slaveSelect!!.size)
        for (i in slaveSelect.indices) {
            slaveSpecs[i] = DigitalOutput.Spec(slaveSelect[i])
        }
        return openSpiMaster(DigitalInput.Spec(miso, DigitalInput.Spec.Mode.PULL_UP),
                DigitalOutput.Spec(mosi), DigitalOutput.Spec(clk),
                slaveSpecs, SpiMaster.Config(rate!!))
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun openSpiMaster(miso: DigitalInput.Spec,
                               mosi: DigitalOutput.Spec, clk: DigitalOutput.Spec,
                               slaveSelect: Array<DigitalOutput.Spec>, config: SpiMaster.Config?): SpiMaster? {
        checkState()
        hardware_!!.checkSupportsPeripheralInput(miso!!.pin)
        hardware_!!.checkSupportsPeripheralOutput(mosi!!.pin)
        hardware_!!.checkSupportsPeripheralOutput(clk!!.pin)
        val ssPins = arrayOfNulls<ResourceManager.Resource>(slaveSelect!!.size)
        val misoPin = ResourceManager.Resource(ResourceType.PIN, miso.pin)
        val mosiPin = ResourceManager.Resource(ResourceType.PIN, mosi.pin)
        val clkPin = ResourceManager.Resource(ResourceType.PIN, clk.pin)
        for (i in slaveSelect.indices) {
            ssPins[i] = ResourceManager.Resource(ResourceType.PIN, slaveSelect[i]!!.pin)
        }
        val spi = ResourceManager.Resource(ResourceType.SPI)
        resourceManager_!!.alloc(ssPins, misoPin, mosiPin, clkPin, spi)
        val result = SpiMasterImpl(this, spi, mosiPin, misoPin,
                clkPin, ssPins)
        addDisconnectListener(result)
        incomingState_.addSpiListener(spi.id, result)
        try {
            protocol_!!.setPinDigitalIn(miso.pin, miso.mode)
            protocol_!!.setPinSpi(miso.pin, 1, true, spi.id)
            protocol_!!.setPinDigitalOut(mosi.pin, true, mosi.mode)
            protocol_!!.setPinSpi(mosi.pin, 0, true, spi.id)
            protocol_!!.setPinDigitalOut(clk.pin, config!!.invertClk, clk.mode)
            protocol_!!.setPinSpi(clk.pin, 2, true, spi.id)
            for (spec in slaveSelect) {
                protocol_!!.setPinDigitalOut(spec!!.pin, true, spec.mode)
            }
            protocol_!!.spiConfigureMaster(spi.id, config)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openPulseInput(spec: DigitalInput.Spec, rate: ClockRate, mode: PulseMode, doublePrecision: Boolean): PulseInput? {
        checkState()
        hardware_!!.checkSupportsPeripheralInput(spec!!.pin)
        val pin = ResourceManager.Resource(ResourceType.PIN, spec.pin)
        val incap = ResourceManager.Resource(
                if (doublePrecision) ResourceType.INCAP_DOUBLE else ResourceType.INCAP_SINGLE)
        resourceManager_!!.alloc(pin, incap)
        val result = IncapImpl(this, mode, incap, pin, rate!!.hertz,
                mode!!.scaling, doublePrecision)
        addDisconnectListener(result)
        incomingState_.addIncapListener(incap.id, result)
        try {
            protocol_!!.setPinDigitalIn(spec.pin, spec.mode)
            protocol_!!.setPinIncap(spec.pin, incap.id, true)
            protocol_!!.incapConfigure(incap.id, doublePrecision,
                    mode.ordinal + 1, rate.ordinal)
        } catch (e: IOException) {
            result.close()
            throw ConnectionLostException(e)
        }
        return result
    }

    @Throws(ConnectionLostException::class)
    override fun openPulseInput(pin: Int, mode: PulseMode): PulseInput? {
        return openPulseInput(DigitalInput.Spec(pin), ClockRate.RATE_16MHz, mode, true)
    }

    @Throws(ConnectionLostException::class)
    override fun openSequencer(config: Array<ChannelConfig>): Sequencer? {
        return SequencerImpl(this, config)
    }

    @Throws(ConnectionLostException::class)
    private fun checkState() {
        if (state === IOIO.State.DEAD) {
            throw ConnectionLostException()
        }
        check(!(state === IOIO.State.INCOMPATIBLE)) { "Incompatibility has been reported - IOIO cannot be used" }
        check(!(state !== IOIO.State.CONNECTED)) { "Connection has not yet been established" }
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun beginBatch() {
        checkState()
        protocol_!!.beginBatch()
    }

    @Synchronized
    @Throws(ConnectionLostException::class)
    override fun endBatch() {
        checkState()
        try {
            protocol_!!.endBatch()
        } catch (e: IOException) {
            throw ConnectionLostException(e)
        }
    }

    @Throws(ConnectionLostException::class, InterruptedException::class)
    override fun sync() {
        var added = false
        val listener = SyncListener()
        try {
            synchronized(this) {
                checkState()
                incomingState_.addSyncListener(listener)
                addDisconnectListener(listener)
                added = true
                try {
                    protocol_!!.sync()
                } catch (e: IOException) {
                    throw ConnectionLostException(e)
                }
            }
            listener.waitSync()
        } finally {
            if (added) {
                removeDisconnectListener(listener)
            }
        }
    }

    private class SyncListener : IncomingState.SyncListener, DisconnectListener {
        private var state_ = State.WAITING

        @Synchronized
        override fun sync() {
            state_ = State.SIGNALED
            notifyAll()
        }

        @Synchronized
        @Throws(InterruptedException::class, ConnectionLostException::class)
        fun waitSync() {
            while (state_ == State.WAITING) {
                wait()
            }
            if (state_ == State.DISCONNECTED) {
                throw ConnectionLostException()
            }
        }

        @Synchronized
        override fun disconnected() {
            state_ = State.DISCONNECTED
            notifyAll()
        }

        internal enum class State {
            WAITING, SIGNALED, DISCONNECTED
        }
    }

    companion object {
        private const val TAG = "IOIOImpl"
        private val REQUIRED_INTERFACE_ID = byteArrayOf('I'.toByte(), 'O'.toByte(),
                'I'.toByte(), 'O'.toByte(), '0'.toByte(), '0'.toByte(), '0'.toByte(), '5'.toByte())
    }
}