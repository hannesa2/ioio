/*
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
package ioio.lib.api

import ioio.lib.api.exception.ConnectionLostException

/**
 * A pin used for digital input.
 *
 *
 * A digital input pin can be used to read logic-level signals. DigitalInput
 * instances are obtained by calling [IOIO.openDigitalInput].
 *
 *
 * The value of the pin is obtained by calling [.read]. It is also
 * possible for the client to block until a certain level is sensed, by using
 * [.waitForValue].
 *
 *
 * The instance is alive since its creation. The first [.read] call
 * block for a few milliseconds until the initial value is updated. If the
 * connection with the IOIO drops at any point, the instance transitions to a
 * disconnected state, in which every attempt to use the pin (except
 * [.close]) will throw a [ConnectionLostException]. Whenever
 * [.close] is invoked the instance may no longer be used. Any resources
 * associated with it are freed and can be reused.
 *
 *
 * Typical usage:
 *
 * <pre>
 * DigitalInput button = ioio.openDigitalInput(10);  // used an external pull-up
 * button.waitForValue(false);  // wait for press
 * ...
 * button.close();  // pin 10 can now be used for something else.
</pre> *
 */
interface DigitalInput : Closeable {
    /**
     * Read the value sensed on the pin. May block for a few milliseconds if
     * called right after creation of the instance. If this is a problem, the
     * calling thread may be interrupted.
     *
     * @return True for logical "HIGH", false for logical "LOW".
     * @throws InterruptedException    The calling thread has been interrupted.
     * @throws ConnectionLostException The connection with the IOIO has been lost.
     */
    @Throws(InterruptedException::class, ConnectionLostException::class)
    fun read(): Boolean

    /**
     * Block until a desired logical level is sensed. The calling thread can be
     * interrupted for aborting this operation.
     *
     * @param value The desired logical level. true for "HIGH", false for "LOW".
     * @throws InterruptedException    The calling thread has been interrupted.
     * @throws ConnectionLostException The connection with the IOIO has been lost.
     */
    @Throws(InterruptedException::class, ConnectionLostException::class)
    fun waitForValue(value: Boolean)

    /**
     * A digital input pin specification, used when opening digital inputs.
     */
    class Spec
    /**
     * Shorthand for Spec(pin, Mode.FLOATING).
     */ @JvmOverloads constructor(
            /**
             * The pin number, as labeled on the board.
             */
            var pin: Int,
            /**
             * The pin mode.
             */
            var mode: Mode = Mode.FLOATING) {
        /**
         * Input pin mode.
         */
        enum class Mode {
            /**
             * Pin is floating. When the pin is left disconnected the value
             * sensed is undefined. Use this mode when an external pull-up or
             * pull-down resistor is used or when interfacing push-pull type
             * logic circuits.
             */
            FLOATING,

            /**
             * Internal pull-up resistor is used. When the pin is left
             * disconnected, a logical "HIGH" (true) will be sensed. This is
             * useful for interfacing with open drain circuits or for
             * interacting with a switch connected between the pin and ground.
             */
            PULL_UP,

            /**
             * Internal pull-down resistor is used. When the pin is left
             * disconnected, a logical "LOW" (false) will be sensed. This is
             * useful for interacting with a switch connected between the pin
             * and Vdd.
             */
            PULL_DOWN
        }
        /**
         * Constructor.
         *
         * @param pin  Pin number, as labeled on the board.
         * @param mode Pin mode.
         */
    }
}