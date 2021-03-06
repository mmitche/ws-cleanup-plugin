/*
 * The MIT License
 *
 * Copyright (c) 2014 Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ws_cleanup;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.remoting.VirtualChannel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.resourcedisposer.AsyncResourceDisposer;
import org.jenkinsci.plugins.resourcedisposer.Disposable;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Cleanup workspace wiping it out completely.
 *
 * This implementation renames workspace directory and let Jenkins to create
 * new one. Old workspace is removed asynchronously.
 *
 * @author ogondza
 */
/*package final*/ class Wipeout extends RemoteCleaner {

    /*private final*/ static Wipeout INSTANCE = new Wipeout();

    /*package*/ static Wipeout getInstance() {
        return INSTANCE;
    }

    @Override
    protected void perform(FilePath workspace) throws IOException, InterruptedException {
        final FilePath deleteMe = getWipeoutWorkspace(workspace);
        Computer computer =  workspace.toComputer();
        if (computer == null) {
            workspace.deleteRecursive();
            return;
        }

        workspace.renameTo(deleteMe);
        if (!deleteMe.exists()) {
            LOGGER.log(
                    Level.WARNING,
                    "Cleaning workspace synchronously. Failed to rename {0} to {1}.",
                    new Object[] { workspace.getRemote(), deleteMe.getName() }
            );
            workspace.deleteRecursive();
            return;
        }

        AsyncResourceDisposer.get().dispose(new DisposableImpl(deleteMe, computer.getName()));
    }

    /*package for testing*/ FilePath getWipeoutWorkspace(FilePath workspace) {
        return workspace.withSuffix("_ws-cleanup_" + System.currentTimeMillis());
    }

    private final static class DisposableImpl implements Disposable {
        private static final long serialVersionUID = 1L;

        // TODO node can get renamed which should be reflected here
        private final String node;
        private final String path;
        @SuppressFBWarnings("SE_TRANSIENT_FIELD_NOT_RESTORED")
        private transient FilePath ws;

        private DisposableImpl(FilePath ws, String computer) {
            this.ws = ws;
            this.node = computer;
            this.path = ws.getRemote();
        }

        @Nonnull public State dispose() throws Throwable {
            Jenkins j = Jenkins.getInstance();
            if (j == null) return State.TO_DISPOSE; // Going down?

            if (ws == null) {
                Computer computer = j.getComputer(node);
                if (computer == null) return State.PURGED; // Removed or discarded cloud machine

                ws = new FilePath(computer.getChannel(), path);
            }
            try {
                ws.deleteRecursive();
            } catch (IOException ex) {
                Throwable cause = ex.getCause();
                if (cause != null && ex.getMessage().startsWith("remote file operation failed:")) {
                    throw cause;
                }
                throw ex;
            }

            return ws.exists()
                ? State.TO_DISPOSE // Failed to delete silently
                : State.PURGED
            ;
        }

        @Nonnull public String getDisplayName() {
            return "Workspace " + (node.isEmpty() ? "master" : node) + ':' + path;
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Wipeout.class.getName());
}
