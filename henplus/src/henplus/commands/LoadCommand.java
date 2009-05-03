/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.HenPlus;
import henplus.Interruptable;
import henplus.SQLSession;
import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.SigIntHandler;

import java.util.StringTokenizer;
import java.util.Iterator;
import java.util.Set;
import java.util.Stack;
import java.util.HashSet;
import java.io.File;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * The Load command loads scripts; it implemnts the commands 'load', 'start',
 * '@' and '@@'.
 */
public class LoadCommand extends AbstractCommand implements Interruptable {
    /**
     * to determine recursively loaded files, we remember all open files.
     */
    private final Set/* <File> */_openFiles;

    /**
     * current working directory stack - to always open files relative to the
     * currently open file.
     */
    private final Stack/* <File> */_cwdStack;

    private volatile boolean _running;

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
        return new String[] { "load", "start", "@", "@@" };
    }

    public LoadCommand() {
        _openFiles = new HashSet();
        _cwdStack = new Stack();
        try {
            final File cwd = new File(".");
            _cwdStack.push(cwd.getCanonicalFile());
        } catch (final IOException e) {
            HenPlus.msg().println(
                    "cannot determine current working directory: " + e);
        }
    }

    /**
     * filename completion by default.
     */
    @Override
    public Iterator complete(final CommandDispatcher disp, final String partialCommand,
            final String lastWord) {
        return new FileCompletionIterator(partialCommand, lastWord);
    }

    /**
     * open a file. If this is a relative filename, then open according to
     * current working directory.
     * 
     * @param filename
     *            the filename to open
     * @return a file that represents the correct file name.
     */
    File openFile(final String filename) {
        File f = new File(filename);
        if (!f.isAbsolute()) {
            f = new File((File) _cwdStack.peek(), filename);
        }
        return f;
    }

    /**
     * execute the command given.
     */
    public int execute(final SQLSession session, final String cmd, final String param) {
        final StringTokenizer st = new StringTokenizer(param);
        final int argc = st.countTokens();
        if (argc < 1) {
            return SYNTAX_ERROR;
        }
        final HenPlus henplus = HenPlus.getInstance();
        while (st.hasMoreElements()) {
            int commandCount = 0;
            final String filename = (String) st.nextElement();
            final long startTime = System.currentTimeMillis();
            File currentFile = null;
            try {
                henplus.pushBuffer();
                henplus.getDispatcher().startBatch();
                File f = openFile(filename);
                f = f.getCanonicalFile();
                if (_openFiles.contains(f)) {
                    throw new IOException(
                            "recursive inclusion alert: skipping file "
                            + f.getName());
                }
                HenPlus.msg().println(f.getName());
                currentFile = f;
                _openFiles.add(currentFile);
                _cwdStack.push(currentFile.getParentFile());
                final String encoding = System.getProperty("file.encoding");//"UTF-8";
                final FileInputStream is = new FileInputStream(currentFile);
                final BufferedReader reader = new BufferedReader(
                        new InputStreamReader(is, encoding));
                _running = true;
                SigIntHandler.getInstance().pushInterruptable(this);
                String line;
                while (_running && (line = reader.readLine()) != null) {
                    final byte execResult = henplus.executeLine(line);
                    if (execResult == HenPlus.LINE_EXECUTED) {
                        ++commandCount;
                    }
                }
            } catch (final Exception e) {
                // e.printStackTrace();
                HenPlus.msg().println(e.getMessage());
                if (st.hasMoreElements()) {
                    HenPlus.msg().println("..skipping to next file.");
                    continue;
                }
                return EXEC_FAILED;
            } finally {
                henplus.popBuffer(); // no open state ..
                if (!_running) {
                    HenPlus.msg().println("cancel file loading " + currentFile);
                }
                henplus.getDispatcher().endBatch();
                if (currentFile != null) {
                    _openFiles.remove(currentFile);
                    _cwdStack.pop();
                }
                SigIntHandler.getInstance().popInterruptable();

            }
            final long execTime = System.currentTimeMillis() - startTime;
            HenPlus.msg().print(commandCount + " commands in ");
            TimeRenderer.printTime(execTime, HenPlus.msg());
            if (commandCount != 0) {
                HenPlus.msg().print("; avg. time ");
                TimeRenderer.printFraction(execTime, commandCount, HenPlus
                        .msg());
            }
            if (execTime != 0 && commandCount > 0) {
                HenPlus.msg()
                .print(
                        "; " + 1000 * commandCount / execTime
                        + " per second");
            }
            HenPlus.msg().println(" (" + filename + ")");
        }
        return SUCCESS;
    }

    public void interrupt() {
        _running = false;
    }

    @Override
    public boolean requiresValidSession(final String cmd) {
        return false;
    }

    /**
     * return a descriptive string.
     */
    @Override
    public String getShortDescription() {
        return "load file and execute commands";
    }

    @Override
    public String getSynopsis(final String cmd) {
        return cmd + " <filename> [<filename> ..]";
    }

    @Override
    public String getLongDescription(final String cmd) {
        return "\tOpens one file or a sequence of files and reads the\n"
        + "\tcontained sql-commands line by line. If the path of the\n"
        + "\tfilename is not absolute, it is interpreted relative to\n"
        + "\tthe current working directory. If the load command itself\n"
        + "\tis executed in some loaded file, then the current working\n"
        + "\tdirectory is the directory that file is in.\n"
        + "\tThe commands 'load' and 'start' do exactly the same;\n"
        + "\t'start', '@' and '@@' are provided for compatibility \n"
        + "\twith oracle SQLPLUS scripts. However, there is no\n"
        + "\tdistinction between '@' and '@@' as in SQLPLUS; henplus\n"
        + "\talways reads subfiles relative to the contained file.\n";
    }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
