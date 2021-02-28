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
 * An interface for controlling an ICSP channel, enabling Flash programming of
 * an external PIC MCU, and in particular, another IOIO board.
 *
 *
 * ICSP (In-Circuit Serial Programming) is a protocol intended for programming
 * of PIC MCUs. It is a serial protocol over three wires: PGC (clock), PGD
 * (data) and MCLR (reset), where PGC and MCLR are controlled by the master and
 * PGD is shared by the master and slave, depending on the transaction state.
 * IcspMaster instances are obtained by calling [IOIO.openIcspMaster].
 *
 *
 * This interface is very low level: it allows direct access to the atomic
 * operations of the ICSP protocol:
 *
 *  * Enter / exit programming mode ( [.enterProgramming] /
 * [.exitProgramming], respectively).
 *  * Executing a single instruction on the slave MCU (
 * [.executeInstruction]).
 *  * Reading the value of the VISI register of the slave MCU into a read queue
 * ([.readVisi]).
 *
 *
 *
 * The ICSP module uses fixed pins for its lines. See the user guide for details
 * for your specific board. ICSP is a special feature, introduced for the
 * purpose of programming a IOIO board with another IOIO board. It does not
 * necessarily play nicely when used concurrently with other features, in the
 * sense that it may introduce latencies in other modules. It is thus
 * recommended not to use ICSP in conjunction with latency-sensitive features.
 *
 *
 * The instance is alive since its creation. If the connection with the IOIO
 * drops at any point, the instance transitions to a disconnected state, in
 * which every attempt to use it (except [.close]) will throw a
 * [ConnectionLostException]. Whenever [.close] is invoked the
 * instance may no longer be used. Any resources associated with it are freed
 * and can be reused.
 *
 *
 * Typical usage:
 *
 * <pre>
 * `IcspMaster icsp = ioio.openIcspMaster();
 * icsp.enterProgramming();
 * icsp.executeInstruction(0x212340);  // mov #0x1234, w0
 * icsp.executeInstruction(0x883C20);  // mov w0, 0x784 (VISI)
 * icsp.executeInstruction(0x000000);  // nop
 * icsp.readVisi();
 * int visi = icsp.waitVisiResult();   // should read 0x1234
 * icsp.exitProgramming();
 * icsp.close();                       // free ICSP module and pins
`</pre> *
 *
 * @see IOIO.openIcspMaster
 */
interface IcspMaster : Closeable {
    /**
     * Initiate a sequence that will put the slave device in programming mode.
     * This sequence is necessary for executing instructions and reading
     * register values.
     *
     * @throws ConnectionLostException Connection to the IOIO has been lost.
     */
    @Throws(ConnectionLostException::class)
    fun enterProgramming()

    /**
     * Initiate a sequence that will put the slave device out of programming
     * mode. It will be held in reset.
     *
     * @throws ConnectionLostException Connection to the IOIO has been lost.
     */
    @Throws(ConnectionLostException::class)
    fun exitProgramming()

    /**
     * Execute a single instruction on the slave MCU.
     *
     * @param instruction a 24-bit PIC instruction.
     * @throws ConnectionLostException Connection to the IOIO has been lost.
     */
    @Throws(ConnectionLostException::class)
    fun executeInstruction(instruction: Int)

    /**
     * Request a read of the VISI register on the slave MCU. This is an
     * asynchronous call, in which the 16-bit result is obtained by
     * [.waitVisiResult].
     * This method may block if the read queue on the IOIO is full, but this
     * should be for short periods only.
     *
     * @throws ConnectionLostException Connection to the IOIO has been lost.
     * @throws InterruptedException    Interrupted while blocking.
     */
    @Throws(ConnectionLostException::class, InterruptedException::class)
    fun readVisi()

    /**
     * Wait and return a result of a call to [.readVisi].
     * Results will be returned in the same order as requested.
     *
     *
     * The call will block until there is data, until interrupted, or until
     * connection to the IOIO has been lost.
     *
     * @return The result - an unsigned 16-bit number.
     * @throws ConnectionLostException Connection to the IOIO has been lost.
     * @throws InterruptedException    Interrupted while blocking.
     */
    @Throws(ConnectionLostException::class, InterruptedException::class)
    fun waitVisiResult(): Int
}