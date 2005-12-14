/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: SigIntHandler.java,v 1.9 2005-12-14 10:53:03 hzeller Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.util.Stack;
import java.util.ListIterator;
import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * Signal handler, that reacts on CTRL-C.
 */
public class SigIntHandler implements SignalHandler, InterruptHandler {
    private static InterruptHandler DUMMY_HANDLER = new InterruptHandler() {
        public void popInterruptable() { }            
        public void pushInterruptable(Interruptable t) { }
        public void reset() { }
    };
    
    private boolean once;
    private static SigIntHandler instance = null;
    private final Stack toInterruptStack;
    
    public static void install() {
	Signal interruptSignal = new Signal("INT"); // Interrupt: Ctrl-C
        instance = new SigIntHandler();
	// don't care about the original handler.
	Signal.handle(interruptSignal, instance);
    }
    
    public static InterruptHandler getInstance() {
        if (instance == null) return DUMMY_HANDLER;
	return instance;
    }

    public SigIntHandler() {
	once = false;
        toInterruptStack = new Stack();
    }
    
    public void pushInterruptable(Interruptable t) {
	toInterruptStack.push(t);
    }
    
    public void popInterruptable() {
        once = false;
        toInterruptStack.pop();
    }

    public void reset() {
	once = false;
	toInterruptStack.clear();
    }

    public void handle(Signal sig) {
	if (once) {
	    // got the interrupt more than once. May happen if you press
            // Ctrl-C multiple times .. or with broken thread lib on Linux.
	    return;
	}

	once = true;
	if (!toInterruptStack.empty()) {
            ListIterator it = toInterruptStack.listIterator(toInterruptStack.size());
            while (it.hasPrevious()) {
                Interruptable toInterrupt = (Interruptable)it.previous();
                toInterrupt.interrupt();
            }
	}
	else {
	    System.err.println("[Ctrl-C ; interrupted]");
	    System.exit(1);
	}
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
