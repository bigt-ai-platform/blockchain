/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.net;

import java.util.Timer;
import java.util.TimerTask;

/**
 * <p>A base class which provides basic support for socket timeouts. It is used instead of integrating timeouts into the
 * NIO select thread both for simplicity and to keep code shared between NIO and blocking sockets as much as possible.
 * </p>
 */
public abstract class AbstractTimeoutHandler {
    // TimerTask and timeout value which are added to a timer to kill the connection on timeout
    private TimerTask timeoutTask;
    private long timeoutMillis = 0;
    private boolean timeoutEnabled = true;

    // A timer which manages expiring channels as their timeouts occur (if configured).
    private static final Timer timeoutTimer = new Timer("AbstractTimeoutHandler timeouts", true);

    /**
     * <p>Enables or disables the timeout entirely. This may be useful if you want to store the timeout value but wish
     * to temporarily disable/enable timeouts.</p>
     *
     * <p>The default is for timeoutEnabled to be true but timeoutMillis to be set to 0 (ie disabled).</p>
     *
     * <p>This call will reset the current progress towards the timeout.</p>
     */
    public synchronized final void setTimeoutEnabled(boolean timeoutEnabled) {
        this.timeoutEnabled = timeoutEnabled;
        resetTimeout();
    }

    /**
     * <p>Sets the receive timeout to the given number of milliseconds, automatically killing the connection if no
     * messages are received for this long</p>
     *
     * <p>A timeout of 0 is interpreted as no timeout.</p>
     *
     * <p>The default is for timeoutEnabled to be true but timeoutMillis to be set to 0 (ie disabled).</p>
     *
     * <p>This call will reset the current progress towards the timeout.</p>
     */
    public synchronized final void setSocketTimeout(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        resetTimeout();
    }

    /**
     * Resets the current progress towards timeout to 0.
     */
    protected synchronized void resetTimeout() {
        if (timeoutTask != null)
            timeoutTask.cancel();
        if (timeoutMillis == 0 || !timeoutEnabled)
            return;
        timeoutTask = new TimerTask() {
            @Override
            public void run() {
                timeoutOccurred();
            }
        };
        timeoutTimer.schedule(timeoutTask, timeoutMillis);
    }

    protected abstract void timeoutOccurred();
}
