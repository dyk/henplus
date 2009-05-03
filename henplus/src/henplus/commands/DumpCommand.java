/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: DumpCommand.java,v 1.38 2006-11-02 17:55:14 hzeller Exp $
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.Interruptable;
import henplus.SQLMetaData;
import henplus.SQLMetaDataBuilder;
import henplus.SQLSession;
import henplus.SigIntHandler;
import henplus.Version;
import henplus.sqlmodel.Table;
import henplus.util.DependencyResolver;
import henplus.util.DependencyResolver.ResolverResult;
import henplus.view.Column;
import henplus.view.ColumnMetaData;
import henplus.view.TableRenderer;
import henplus.view.util.NameCompleter;
import henplus.view.util.CancelWriter;

import henplus.view.util.ProgressWriter;

import java.math.BigDecimal;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Reader;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSetMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.SortedSet;
import java.util.StringTokenizer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Dump out and read that dump of a table; database-independently. This reads
 * directly from the stream, so only needs not much memory, no matter the size
 * of the file. --------------------------- (tabledump 'student' (dump-version 1
 * 1) (henplus-version 0.3.3) (database-info 'MySQL - 3.23.47') (meta ('name',
 * 'sex', 'student_id') ('STRING', 'STRING', 'INTEGER' )) (data ('Megan','F',1)
 * ('Joseph','M',2) ('Kyle','M',3) ('Mac Donald\'s','M',44)) (rows 4))
 * ---------------------------
 * 
 * QUICK AND DIRTY HACK .. NOT YET NICE. Too long. grown. Refactor..! (create an
 * henplus.dump package so that this can be used
 * 
 * @author Henner Zeller
 */
public class DumpCommand extends AbstractCommand implements Interruptable {
    private static final ColumnMetaData META_HEADERS[];
    static {
        META_HEADERS = new ColumnMetaData[3];
        META_HEADERS[0] = new ColumnMetaData("Field");
        META_HEADERS[1] = new ColumnMetaData("Type");
        META_HEADERS[2] = new ColumnMetaData("Max. length found",
                ColumnMetaData.ALIGN_RIGHT);
    }

    private static final String FILE_ENCODING = "UTF-8";
    private static final int DUMP_VERSION = 1;
    private static final String NULL_STR = "NULL";
    private static final Map<Integer, String> JDBCTYPE2TYPENAME = new HashMap<Integer, String>();

    // differentiated types by dump
    private static final String[] TYPES = new String[9];
    private static final int HP_STRING = 0;
    private static final int HP_INTEGER = 1;
    private static final int HP_NUMERIC = 2;
    private static final int HP_DOUBLE = 3;
    private static final int HP_DATE = 4;
    private static final int HP_TIME = 5;
    private static final int HP_TIMESTAMP = 6;
    private static final int HP_BLOB = 7;
    private static final int HP_CLOB = 8;

    static {
        TYPES[HP_STRING] = "STRING";
        TYPES[HP_INTEGER] = "INTEGER";
        TYPES[HP_NUMERIC] = "NUMERIC";
        TYPES[HP_DOUBLE] = "DOUBLE";
        TYPES[HP_DATE] = "DATE";
        TYPES[HP_TIME] = "TIME";
        TYPES[HP_TIMESTAMP] = "TIMESTAMP";
        TYPES[HP_BLOB] = "BLOB";
        TYPES[HP_CLOB] = "CLOB";

        JDBCTYPE2TYPENAME.put(new Integer(Types.CHAR), TYPES[HP_STRING]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.VARCHAR), TYPES[HP_STRING]);

        // hope that, 'OTHER' can be read/written as String..
        JDBCTYPE2TYPENAME.put(new Integer(Types.OTHER), TYPES[HP_STRING]);

        JDBCTYPE2TYPENAME.put(new Integer(Types.LONGVARBINARY), TYPES[HP_BLOB]);
        // CLOB not supported .. try string.
        JDBCTYPE2TYPENAME.put(new Integer(Types.LONGVARCHAR), TYPES[HP_STRING]);

        // not supported yet.
        JDBCTYPE2TYPENAME.put(new Integer(Types.BLOB), TYPES[HP_BLOB]);
        // CLOB not supported .. try string.
        JDBCTYPE2TYPENAME.put(new Integer(Types.CLOB), TYPES[HP_STRING]);

        // generic float.
        JDBCTYPE2TYPENAME.put(new Integer(Types.DOUBLE), TYPES[HP_DOUBLE]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.FLOAT), TYPES[HP_DOUBLE]);

        // generic numeric. could be integer or double
        JDBCTYPE2TYPENAME.put(new Integer(Types.BIGINT), TYPES[HP_NUMERIC]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.NUMERIC), TYPES[HP_NUMERIC]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.DECIMAL), TYPES[HP_NUMERIC]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.BOOLEAN), TYPES[HP_NUMERIC]);
        // generic integer.
        JDBCTYPE2TYPENAME.put(new Integer(Types.INTEGER), TYPES[HP_INTEGER]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.SMALLINT), TYPES[HP_INTEGER]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.TINYINT), TYPES[HP_INTEGER]);

        JDBCTYPE2TYPENAME.put(new Integer(Types.DATE), TYPES[HP_DATE]);
        JDBCTYPE2TYPENAME.put(new Integer(Types.TIME), TYPES[HP_TIME]);
        JDBCTYPE2TYPENAME
        .put(new Integer(Types.TIMESTAMP), TYPES[HP_TIMESTAMP]);
    }

    private final ListUserObjectsCommand _tableCompleter;
    private final LoadCommand _fileOpener;
    private volatile boolean _running;

    public DumpCommand(final ListUserObjectsCommand tc, final LoadCommand lc) {
        _tableCompleter = tc;
        _fileOpener = lc;
        _running = false;
    }

    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
        return new String[] { "dump-out", "dump-in", "verify-dump",
                "dump-conditional", "dump-select" };
    }

    /**
     * verify works without session.
     */
    @Override
    public boolean requiresValidSession(final String cmd) {
        return false;
    }

    /**
     * dump-in and verify-dump is complete as single-liner. dump-out and
     * dump-conditional needs a semicolon.
     */
    @Override
    public boolean isComplete(final String command) {
        if (command.startsWith("dump-in") || command.startsWith("verify-dump")) {
            return true;
        }
        return command.endsWith(";");
    }

    /**
     * execute the command given.
     */
    public int execute(final SQLSession session, final String cmd, final String param) {
        // final String FILE_ENCODING = System.getProperty("file.encoding");
        final StringTokenizer st = new StringTokenizer(param);
        final int argc = st.countTokens();

        if ("dump-select".equals(cmd)) {
            if (session == null) {
                HenPlus.msg().println("not connected.");
                return EXEC_FAILED;
            }
            if (argc < 4) {
                return SYNTAX_ERROR;
            }
            final String fileName = st.nextToken();
            final String tabName = st.nextToken();
            final String select = st.nextToken();
            if (!select.toUpperCase().equals("SELECT")) {
                HenPlus.msg().println("'select' expected..");
                return SYNTAX_ERROR;
            }
            final StringBuffer statement = new StringBuffer("select");
            while (st.hasMoreElements()) {
                statement.append(" ").append(st.nextToken());
            }
            PrintStream out = null;
            beginInterruptableSection();
            try {
                out = openOutputStream(fileName, FILE_ENCODING);
                final int result = dumpSelect(session, tabName, statement.toString(),
                        out, FILE_ENCODING);
                return result;
            } catch (final Exception e) {
                HenPlus.msg().println("failed: " + e.getMessage());
                e.printStackTrace();
                return EXEC_FAILED;
            } finally {
                if (out != null) {
                    out.close();
                }
                endInterruptableSection();
            }
        }

        else if ("dump-conditional".equals(cmd)) {
            if (session == null) {
                HenPlus.msg().println("not connected.");
                return EXEC_FAILED;
            }
            if (argc < 2) {
                return SYNTAX_ERROR;
            }
            final String fileName = (String) st.nextElement();
            final String tabName = (String) st.nextElement();
            String whereClause = null;
            if (argc >= 3) {
                whereClause = st.nextToken("\n"); // till EOL
                whereClause = whereClause.trim();
                if (whereClause.toUpperCase().startsWith("WHERE")) {
                    whereClause = whereClause.substring(5);
                    whereClause = whereClause.trim();
                }
            }
            PrintStream out = null;
            beginInterruptableSection();
            try {
                out = openOutputStream(fileName, FILE_ENCODING);
                final int result = dumpTable(session, tabName, whereClause, out,
                        FILE_ENCODING);
                return result;
            } catch (final Exception e) {
                HenPlus.msg().println("failed: " + e.getMessage());
                e.printStackTrace();
                return EXEC_FAILED;
            } finally {
                if (out != null) {
                    out.close();
                }
                endInterruptableSection();
            }
        }

        else if ("dump-out".equals(cmd)) {
            if (session == null) {
                HenPlus.msg().println("not connected.");
                return EXEC_FAILED;
            }
            if (argc < 2) {
                return SYNTAX_ERROR;
            }
            final String fileName = (String) st.nextElement();
            PrintStream out = null;
            final String tabName = null;
            beginInterruptableSection();
            try {
                final long startTime = System.currentTimeMillis();
                final Set alreadyDumped = new HashSet(); // which tables got already
                // dumped?

                out = openOutputStream(fileName, FILE_ENCODING);
                final Set/* <String> */tableSet = new LinkedHashSet();

                /*
                 * right now, we do only a sort, if there is any '*' found in
                 * tables. Probably we might want to make this an option to
                 * dump-in
                 */
                boolean needsSort = false;

                int dumpResult = SUCCESS;

                /* 1) collect tables */
                while (st.hasMoreElements()) {
                    final String nextToken = st.nextToken();

                    if ("*".equals(nextToken) || nextToken.indexOf('*') > -1) {
                        needsSort = true;

                        Iterator iter = null;

                        if ("*".equals(nextToken)) {
                            iter = _tableCompleter
                            .getTableNamesIteratorForSession(session);
                        } else if (nextToken.indexOf('*') > -1) {
                            final String tablePrefix = nextToken.substring(0,
                                    nextToken.length() - 1);
                            final SortedSet tableNames = _tableCompleter
                            .getTableNamesForSession(session);
                            final NameCompleter compl = new NameCompleter(tableNames);
                            iter = compl.getAlternatives(tablePrefix);
                        }
                        while (iter.hasNext()) {
                            tableSet.add(iter.next());
                        }
                    } else {
                        tableSet.add(nextToken);
                    }
                }

                /* 2) resolve dependencies */
                ResolverResult resolverResult = null;
                List<String> tableSequence;
                if (needsSort) {
                    tableSequence = new ArrayList<String>();
                    HenPlus
                    .msg()
                    .println(
                            "Retrieving and sorting tables. This may take a while, please be patient.");

                    // get sorted tables
                    final SQLMetaData meta = new SQLMetaDataBuilder().getMetaData(
                            session, tableSet.iterator());
                    final DependencyResolver dr = new DependencyResolver(meta
                            .getTables());
                    resolverResult = dr.sortTables();
                    final List/* <Table> */tabs = resolverResult.getTables();
                    final Iterator it = tabs.iterator();
                    while (it.hasNext()) {
                        tableSequence.add(((Table) it.next()).getName());
                    }
                } else {
                    tableSequence = new ArrayList<String>(tableSet);
                }

                /* 3) dump out */
                if (tableSequence.size() > 1) {
                    HenPlus.msg().println(
                            tableSequence.size() + " tables to dump.");
                }
                final Iterator it = tableSequence.iterator();
                while (_running && it.hasNext()) {
                    final String table = (String) it.next();
                    if (!alreadyDumped.contains(table)) {
                        final int result = dumpTable(session, table, null, out,
                                FILE_ENCODING, alreadyDumped);
                        if (result != SUCCESS) {
                            dumpResult = result;
                        }
                    }
                }

                if (tableSequence.size() > 1) {
                    final long duration = System.currentTimeMillis()
                    - startTime;
                    HenPlus.msg()
                    .print(
                            "Dumping " + tableSequence.size()
                            + " tables took ");
                    TimeRenderer.printTime(duration, HenPlus.msg());
                    HenPlus.msg().println();
                }

                /* 4) warn about cycles */
                if (resolverResult != null
                        && resolverResult.getCyclicDependencies() != null
                        && resolverResult.getCyclicDependencies().size() > 0) {
                    HenPlus
                    .msg()
                    .println(
                            "-----------\n"
                            + "NOTE: There have been cyclic dependencies between several tables detected.\n"
                            + "These may cause trouble when dumping in the currently dumped data.");
                    final Iterator iter = resolverResult.getCyclicDependencies()
                    .iterator();
                    final int count = 0;
                    final StringBuffer sb = new StringBuffer();
                    while (iter.hasNext()) {
                        final Iterator iter2 = ((List) iter.next()).iterator();
                        sb.append("Cycle ").append(count).append(": ");

                        while (iter2.hasNext()) {
                            sb.append(((Table) iter2.next()).getName()).append(
                            " -> ");
                        }
                        sb.delete(sb.length() - 4, sb.length()).append('\n');
                    }
                    HenPlus.msg().print(sb.toString());
                    /* todo: print out, what constraint to disable */
                }

                return dumpResult;
            } catch (final Exception e) {
                HenPlus.msg().println(
                        "dump table '" + tabName + "' failed: "
                        + e.getMessage());
                e.printStackTrace();
                return EXEC_FAILED;
            } finally {
                if (out != null) {
                    out.close();
                }
                endInterruptableSection();
            }
        }

        else if ("dump-in".equals(cmd)) {
            if (session == null) {
                HenPlus.msg().println(
                "not connected. Only verify-dump possible.");
                return EXEC_FAILED;
            }
            if (argc < 1 || argc > 2) {
                return SYNTAX_ERROR;
            }
            final String fileName = (String) st.nextElement();
            int commitPoint = -1;
            if (argc == 2) {
                try {
                    final String val = (String) st.nextElement();
                    commitPoint = Integer.valueOf(val).intValue();
                } catch (final NumberFormatException e) {
                    HenPlus.msg().println("commit point number expected: " + e);
                    return SYNTAX_ERROR;
                }
            }
            return retryReadDump(fileName, session, commitPoint);
        }

        else if ("verify-dump".equals(cmd)) {
            if (argc != 1) {
                return SYNTAX_ERROR;
            }
            final String fileName = (String) st.nextElement();
            return retryReadDump(fileName, null, -1);
        }
        return SYNTAX_ERROR;
    }

    /**
     * reads a dump and does a retry if the file encoding does not match.
     */
    private int retryReadDump(final String fileName, final SQLSession session,
            final int commitPoint) {
        LineNumberReader in = null;
        final boolean hot = session != null;
        beginInterruptableSection();
        try {
            String fileEncoding = FILE_ENCODING;
            boolean retryPossible = true;
            do {
                try {
                    in = openInputReader(fileName, fileEncoding);
                    while (skipWhite(in)) {
                        final int result = readTableDump(in, fileEncoding, session,
                                hot, commitPoint);
                        retryPossible = false;
                        if (!_running) {
                            HenPlus.msg().println("interrupted.");
                            return result;
                        }
                        if (result != SUCCESS) {
                            return result;
                        }
                    }
                } catch (final EncodingMismatchException e) {
                    // did we already retry with another encoding?
                    if (!fileEncoding.equals(FILE_ENCODING)) {
                        throw new Exception("got file encoding problem twice");
                    }
                    fileEncoding = e.getEncoding();
                    HenPlus.msg().println(
                            "got a different encoding; retry with "
                            + fileEncoding);
                }
            } while (retryPossible);
            return SUCCESS;
        } catch (final Exception e) {
            HenPlus.msg().println("failed: " + e.getMessage());
            e.printStackTrace();
            return EXEC_FAILED;
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (final IOException e) {
                HenPlus.msg().println("closing file failed.");
            }
            endInterruptableSection();
        }
    }

    private PrintStream openOutputStream(final String fileName, final String encoding)
    throws IOException {
        final File f = _fileOpener.openFile(fileName);
        OutputStream outStream = new FileOutputStream(f);
        if (fileName.endsWith(".gz")) {
            outStream = new GZIPOutputStream(outStream, 4096);
        }
        return new PrintStream(outStream, false, encoding);
    }

    private LineNumberReader openInputReader(final String fileName,
            final String fileEncoding) throws IOException {
        final File f = _fileOpener.openFile(fileName);
        InputStream inStream = new FileInputStream(f);
        if (fileName.endsWith(".gz")) {
            inStream = new GZIPInputStream(inStream);
        }
        final Reader fileIn = new InputStreamReader(inStream, fileEncoding);
        return new LineNumberReader(fileIn);
    }

    // to make the field-name and field-type nicely aligned
    private void printWidth(final PrintStream out, final String s, final int width, final boolean comma) {
        if (comma) {
            out.print(", ");
        }
        out.print("'");
        out.print(s);
        out.print("'");
        for (int i = s.length(); i < width; ++i) {
            out.print(' ');
        }
    }

    private int dumpTable(final SQLSession session, final String tabName,
            final String whereClause, final PrintStream dumpOut, final String fileEncoding,
            final Set/* <String> */alreadyDumped) throws Exception {
        final int result = dumpTable(session, tabName, whereClause, dumpOut,
                fileEncoding);
        alreadyDumped.add(tabName);
        return result;
    }

    private int dumpSelect(final SQLSession session, final String exportTable,
            final String statement, final PrintStream dumpOut, final String fileEncoding)
    throws Exception {
        return dumpTable(session, new SelectDumpSource(session, exportTable,
                statement), dumpOut, fileEncoding);
    }

    private int dumpTable(final SQLSession session, String tabName,
            final String whereClause, final PrintStream dumpOut, final String fileEncoding)
    throws Exception {

        // asking for meta data is only possible with the correct
        // table name.
        boolean correctName = true;
        if (tabName.startsWith("\"")) {
            // tabName = stripQuotes(tabName);
            correctName = false;
        }

        // separate schama and table.
        String schema = null;
        final int schemaDelim = tabName.indexOf('.');
        if (schemaDelim > 0) {
            schema = tabName.substring(0, schemaDelim);
            tabName = tabName.substring(schemaDelim + 1);
        }

        if (correctName) {
            final String alternative = _tableCompleter.correctTableName(tabName);
            if (alternative != null && !alternative.equals(tabName)) {
                tabName = alternative;
                HenPlus.out().println(
                        "dumping table: '" + tabName + "' (corrected name)");
            }
        }
        final TableDumpSource tableSource = new TableDumpSource(schema,
                tabName, !correctName, session);
        tableSource.setWhereClause(whereClause);
        return dumpTable(session, tableSource, dumpOut, fileEncoding);
    }

    private int dumpTable(final SQLSession session, final DumpSource dumpSource,
            final PrintStream dumpOut, final String fileEncoding) throws Exception {
        final long startTime = System.currentTimeMillis();
        final MetaProperty[] metaProps = dumpSource.getMetaProperties();
        if (metaProps.length == 0) {
            HenPlus.msg().println(
                    "No fields in " + dumpSource.getDescription() + " found.");
            return EXEC_FAILED;
        }

        HenPlus.msg().println("dump " + dumpSource.getTableName() + ":");

        dumpOut.println("(tabledump '" + dumpSource.getTableName() + "'");
        dumpOut.println("  (file-encoding '" + fileEncoding + "')");
        dumpOut.println("  (dump-version " + DUMP_VERSION + " " + DUMP_VERSION
                + ")");
        /*
         * if (whereClause != null) { dumpOut.print("  (where-clause ");
         * quoteString(dumpOut, whereClause); dumpOut.println(")"); }
         */
        dumpOut.println("  (henplus-version '" + Version.getVersion() + "')");
        dumpOut.println("  (time '" + new Timestamp(System.currentTimeMillis())
        + "')");
        dumpOut.print("  (database-info ");
        quoteString(dumpOut, session.getDatabaseInfo());
        dumpOut.println(")");

        final long expectedRows = dumpSource.getExpectedRows();
        dumpOut.println("  (estimated-rows '" + expectedRows + "')");

        dumpOut.print("  (meta (");
        for (int i = 0; i < metaProps.length; ++i) {
            final MetaProperty p = metaProps[i];
            printWidth(dumpOut, p.fieldName, p.renderWidth(), i != 0);
        }
        dumpOut.println(")");
        dumpOut.print("\t(");
        for (int i = 0; i < metaProps.length; ++i) {
            final MetaProperty p = metaProps[i];
            printWidth(dumpOut, p.typeName, p.renderWidth(), i != 0);
        }
        dumpOut.println("))");

        dumpOut.print("  (data ");
        ResultSet rset = null;
        Statement stmt = null;
        try {
            long rows = 0;
            final ProgressWriter progressWriter = new ProgressWriter(expectedRows,
                    HenPlus.msg());
            rset = dumpSource.getResultSet();
            stmt = dumpSource.getStatement();
            boolean isFirst = true;
            while (_running && rset.next()) {
                ++rows;
                progressWriter.update(rows);
                if (!isFirst) {
                    dumpOut.print("\n\t");
                }
                isFirst = false;
                dumpOut.print("(");

                for (int i = 0; i < metaProps.length; ++i) {
                    final int col = i + 1;
                    final int thisType = metaProps[i].getType();
                    switch (thisType) {
                    case HP_INTEGER:
                    case HP_NUMERIC:
                    case HP_DOUBLE: {
                        final String val = rset.getString(col);
                        if (rset.wasNull()) {
                            dumpOut.print(NULL_STR);
                        } else {
                            dumpOut.print(val);
                        }
                        break;
                    }

                    case HP_TIMESTAMP: {
                        final Timestamp val = rset.getTimestamp(col);
                        if (rset.wasNull()) {
                            dumpOut.print(NULL_STR);
                        } else {
                            quoteString(dumpOut, val.toString());
                        }
                        break;
                    }

                    case HP_TIME: {
                        final Time val = rset.getTime(col);
                        if (rset.wasNull()) {
                            dumpOut.print(NULL_STR);
                        } else {
                            quoteString(dumpOut, val.toString());
                        }
                        break;
                    }

                    case HP_DATE: {
                        final java.sql.Date val = rset.getDate(col);
                        if (rset.wasNull()) {
                            dumpOut.print(NULL_STR);
                        } else {
                            quoteString(dumpOut, val.toString());
                        }
                        break;
                    }

                    case HP_BLOB: // we try our best by reading BLOB/CLOB
                    case HP_CLOB: // as String (known not to work on Oracle)
                    case HP_STRING: {
                        final String val = rset.getString(col);
                        if (rset.wasNull()) {
                            dumpOut.print(NULL_STR);
                        } else {
                            quoteString(dumpOut, val);
                        }
                        break;
                    }

                    default:
                        throw new IllegalArgumentException("type "
                                + TYPES[thisType] + " not supported yet");
                    }
                    if (metaProps.length > col) {
                        dumpOut.print(",");
                    } else {
                        dumpOut.print(")");
                    }
                }
            }
            progressWriter.finish();
            dumpOut.println(")");
            dumpOut.println("  (rows " + rows + "))\n");

            HenPlus.msg().print("(" + rows + " rows)\n");
            final long execTime = System.currentTimeMillis() - startTime;

            HenPlus.msg().print(
                    "dumping '" + dumpSource.getTableName() + "' took ");
            TimeRenderer.printTime(execTime, HenPlus.msg());
            HenPlus.msg().print(" total; ");
            TimeRenderer.printFraction(execTime, rows, HenPlus.msg());
            HenPlus.msg().println(" / row");
            if (expectedRows >= 0 && rows != expectedRows) {
                HenPlus.msg().println(
                        " == Warning: 'select count(*)' in the"
                        + " beginning resulted in " + expectedRows
                        + " but the dump exported " + rows
                        + " rows == ");
            }

            if (!_running) {
                HenPlus.msg().println(
                " == INTERRUPTED. Wait for statement to cancel.. ==");
                if (stmt != null) {
                    stmt.cancel();
                }
            }
        } catch (final Exception e) {
            // HenPlus.msg().println(selectStmt.toString());
            throw e; // handle later.
        } finally {
            if (rset != null) {
                try {
                    rset.close();
                } catch (final Exception e) {
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (final Exception e) {
                }
            }
        }
        return SUCCESS;
    }

    private Number readNumber(final LineNumberReader in) throws IOException {
        String token = readToken(in);
        // separated sign.
        if (token.length() == 1 && (token.equals("+") || token.equals("-"))) {
            token += readToken(in);
        }
        if (token.equals(NULL_STR)) {
            return null;
        }
        try {
            if (token.indexOf('.') > 0) {
                return Double.valueOf(token);
            }
            if (token.length() < 10) {
                return Integer.valueOf(token);
            } else if (token.length() < 19) {
                return Long.valueOf(token);
            } else {
                return new BigDecimal(token);
            }
        } catch (final NumberFormatException e) {
            raiseException(in, "Number format " + token + ": " + e.getMessage());
        }
        return null;
    }

    private int readTableDump(final LineNumberReader reader, final String fileEncoding,
            final SQLSession session, final boolean hot, final int commitPoint)
    throws IOException, SQLException, InterruptedException {
        MetaProperty[] metaProperty = null;
        String tableName = null;
        int dumpVersion = -1;
        int compatibleVersion = -1;
        String henplusVersion = null;
        String databaseInfo = null;
        String dumpTime = null;
        String whereClause = null;
        String token;
        long importedRows = -1;
        long expectedRows = -1;
        long estimatedRows = -1;
        long problemRows = -1;
        Connection conn = null;
        PreparedStatement stmt = null;

        expect(reader, '(');
        token = readToken(reader);
        if (!"tabledump".equals(token)) {
            raiseException(reader, "'tabledump' expected");
        }
        tableName = readString(reader);
        final long startTime = System.currentTimeMillis();
        while (_running) {
            skipWhite(reader);
            final int rawChar = reader.read();
            if (rawChar == -1) {
                return SUCCESS; // EOF reached.
            }
            char inCh = (char) rawChar;
            if (inCh == ')') {
                break;
            }
            if (inCh != '(') {
                raiseException(reader, "'(' or ')' expected");
            }
            token = readToken(reader);

            if ("dump-version".equals(token)) {
                token = readToken(reader);
                try {
                    dumpVersion = Integer.valueOf(token).intValue();
                } catch (final Exception e) {
                    raiseException(reader, "expected dump version number");
                }
                token = readToken(reader);
                try {
                    compatibleVersion = Integer.valueOf(token).intValue();
                } catch (final Exception e) {
                    raiseException(reader, "expected compatible version number");
                }
                checkSupported(compatibleVersion);
                expect(reader, ')');
            }

            else if ("file-encoding".equals(token)) {
                token = readString(reader);
                if (!token.equals(fileEncoding)) {
                    throw new EncodingMismatchException(token);
                }
                expect(reader, ')');
            }

            else if ("henplus-version".equals(token)) {
                token = readString(reader);
                henplusVersion = token;
                expect(reader, ')');
            }

            else if ("rows".equals(token)) {
                token = readToken(reader);
                expectedRows = Integer.valueOf(token).intValue();
                expect(reader, ')');
            }

            else if ("estimated-rows".equals(token)) {
                token = readString(reader);
                estimatedRows = Integer.valueOf(token).intValue();
                expect(reader, ')');
            }

            else if ("database-info".equals(token)) {
                databaseInfo = readString(reader);
                expect(reader, ')');
            }

            else if ("where-clause".equals(token)) {
                whereClause = readString(reader);
                expect(reader, ')');
            }

            else if ("time".equals(token)) {
                dumpTime = readString(reader);
                expect(reader, ')');
            }

            else if ("meta".equals(token)) {
                if (dumpVersion < 0 || compatibleVersion < 0) {
                    raiseException(reader,
                    "cannot read meta data without dump-version information");
                }
                metaProperty = parseMetaData(reader);
            }

            else if ("data".equals(token)) {
                if (metaProperty == null) {
                    raiseException(reader, "no meta-data available");
                }
                if (tableName == null) {
                    raiseException(reader, "no table name known");
                }
                if (hot) {
                    final StringBuffer prep = new StringBuffer("INSERT INTO ");
                    prep.append(tableName);
                    prep.append(" (");
                    for (int i = 0; i < metaProperty.length; ++i) {
                        prep.append(metaProperty[i].fieldName);
                        if (i + 1 < metaProperty.length) {
                            prep.append(",");
                        }
                    }
                    prep.append(") VALUES (");
                    for (int i = 0; i < metaProperty.length; ++i) {
                        prep.append("?");
                        if (i + 1 < metaProperty.length) {
                            prep.append(",");
                        }
                    }
                    prep.append(")");
                    // HenPlus.msg().println(prep.toString());
                    conn = session.getConnection();
                    stmt = conn.prepareStatement(prep.toString());
                }

                HenPlus.msg().println(
                        (hot ? "importing" : "verifying")
                        + " table dump created with HenPlus "
                        + henplusVersion + "\nfor table           : "
                        + tableName + "\nfrom database       : "
                        + databaseInfo + "\nat                  : "
                        + dumpTime + "\ndump format version : "
                        + dumpVersion);
                if (whereClause != null) {
                    HenPlus.msg().println(
                            "projection          : " + whereClause);
                }

                final ProgressWriter progressWriter = new ProgressWriter(
                        estimatedRows, HenPlus.msg());
                importedRows = 0;
                problemRows = 0;
                _running = true;
                while (_running) {
                    skipWhite(reader);
                    inCh = (char) reader.read();
                    if (inCh == ')') {
                        break;
                    }
                    if (inCh != '(') {
                        raiseException(reader, "'(' or ')' expected");
                    }
                    // we are now at the beginning of the row.
                    ++importedRows;
                    progressWriter.update(importedRows);
                    for (int i = 0; i < metaProperty.length; ++i) {
                        final int col = i + 1;
                        final int type = metaProperty[i].type;
                        switch (type) {
                        case HP_NUMERIC:
                        case HP_DOUBLE:
                        case HP_INTEGER: {
                            final Number number = readNumber(reader);
                            if (stmt != null) {
                                if (number == null) {
                                    if (type == HP_NUMERIC) {
                                        stmt.setNull(col, Types.NUMERIC);
                                    } else if (type == HP_INTEGER) {
                                        stmt.setNull(col, Types.INTEGER);
                                    } else if (type == HP_DOUBLE) {
                                        stmt.setNull(col, Types.DOUBLE);
                                    }
                                } else {
                                    if (number instanceof Integer) {
                                        stmt.setInt(col, number.intValue());
                                    } else if (number instanceof Long) {
                                        stmt.setLong(col, number.longValue());
                                    } else if (number instanceof Double) {
                                        stmt.setDouble(col, number
                                                .doubleValue());
                                    } else if (number instanceof BigDecimal) {
                                        stmt.setBigDecimal(col,
                                                (BigDecimal) number);
                                    }
                                }
                            }
                            break;
                        }

                        case HP_TIMESTAMP: {
                            final String val = readString(reader);
                            metaProperty[i].updateMaxLength(val);
                            if (stmt != null) {
                                if (val == null) {
                                    stmt.setTimestamp(col, null);
                                } else {
                                    stmt.setTimestamp(col, Timestamp
                                            .valueOf(val));
                                }
                            }
                            break;
                        }

                        case HP_TIME: {
                            final String val = readString(reader);
                            metaProperty[i].updateMaxLength(val);
                            if (stmt != null) {
                                if (val == null) {
                                    stmt.setTime(col, null);
                                } else {
                                    stmt.setTime(col, Time.valueOf(val));
                                }
                            }
                            break;
                        }

                        case HP_DATE: {
                            final String val = readString(reader);
                            metaProperty[i].updateMaxLength(val);
                            if (stmt != null) {
                                if (val == null) {
                                    stmt.setDate(col, null);
                                } else {
                                    stmt.setDate(col, java.sql.Date
                                            .valueOf(val));
                                }
                            }
                            break;
                        }

                        case HP_BLOB: // we try our best by reading BLOB/CLOB
                        case HP_CLOB: // as String (known not to work on Oracle
                        case HP_STRING: {
                            final String val = readString(reader);
                            metaProperty[i].updateMaxLength(val);
                            if (stmt != null) {
                                stmt.setString(col, val);
                            }
                            break;
                        }

                        default:
                            throw new IllegalArgumentException("type "
                                    + TYPES[metaProperty[i].type]
                                            + " not supported yet");
                        }
                        expect(reader, i + 1 < metaProperty.length ? ','
                                : ')');
                    }
                    try {
                        if (stmt != null) {
                            stmt.execute();
                        }
                    } catch (final SQLException e) {
                        String msg = e.getMessage();
                        // oracle adds CR for some reason.
                        if (msg != null) {
                            msg = msg.trim();
                        }
                        reportProblem(msg);
                        ++problemRows;
                    }

                    // commit every once in a while.
                    if (hot && commitPoint >= 0
                            && importedRows % commitPoint == 0) {
                        conn.commit();
                    }
                }
                progressWriter.finish();
            }

            else {
                HenPlus.msg().println("ignoring unknown token " + token);
                dumpTime = readString(reader);
                expect(reader, ')');
            }
        }

        // return final count.
        finishProblemReports();

        if (!hot) {
            printMetaDataInfo(metaProperty);
        }

        // final commit, if commitPoints are enabled.
        if (hot && commitPoint >= 0) {
            conn.commit();
        }

        // we're done.
        if (stmt != null) {
            try {
                stmt.close();
            } catch (final Exception e) {
            }
        }

        if (expectedRows >= 0 && expectedRows != importedRows) {
            HenPlus.msg().println(
                    "WARNING: expected " + expectedRows + " but got "
                    + importedRows + " rows");
        } else {
            HenPlus.msg().println("ok. ");
        }
        HenPlus.msg().print("(" + importedRows + " rows total");
        if (hot) {
            HenPlus.msg().print(" / " + problemRows + " with errors");
        }
        HenPlus.msg().print("; ");
        final long execTime = System.currentTimeMillis() - startTime;
        TimeRenderer.printTime(execTime, HenPlus.msg());
        HenPlus.msg().print(" total; ");
        TimeRenderer.printFraction(execTime, importedRows, HenPlus.msg());
        HenPlus.msg().println(" / row)");
        return SUCCESS;
    }

    public MetaProperty[] parseMetaData(final LineNumberReader in) throws IOException {
        final List<MetaProperty> metaList = new ArrayList<MetaProperty>();
        expect(in, '(');
        for (;;) {
            final String colName = readString(in);
            metaList.add(new MetaProperty(colName));
            skipWhite(in);
            final char inCh = (char) in.read();
            if (inCh == ')') {
                break;
            }
            if (inCh != ',') {
                raiseException(in, "',' or ')' expected");
            }
        }
        expect(in, '(');
        final MetaProperty[] result = metaList.toArray(new MetaProperty[metaList.size()]);
        for (int i = 0; i < result.length; ++i) {
            final String typeName = readString(in);
            result[i].setTypeName(typeName);
            expect(in, i + 1 < result.length ? ',' : ')');
        }
        expect(in, ')');
        return result;
    }

    String lastProblem = null;
    long problemCount = 0;

    private void reportProblem(final String msg) {
        if (msg == null) {
            return;
        }
        if (msg.equals(lastProblem)) {
            ++problemCount;
        } else {
            finishProblemReports();
            problemCount = 1;
            HenPlus.msg().print("Problem: " + msg);
            lastProblem = msg;
        }
    }

    private void finishProblemReports() {
        if (problemCount > 1) {
            HenPlus.msg().print("   (" + problemCount + " times)");
        }
        if (problemCount > 0) {
            HenPlus.msg().println();
        }
        lastProblem = null;
        problemCount = 0;
    }

    public void checkSupported(final int version) throws IllegalArgumentException {
        if (version <= 0 || version > DUMP_VERSION) {
            throw new IllegalArgumentException("incompatible dump-version");
        }
    }

    public void expect(final LineNumberReader in, final char ch) throws IOException {
        skipWhite(in);
        final char inCh = (char) in.read();
        if (ch != inCh) {
            raiseException(in, "'" + ch + "' expected");
        }
    }

    private void quoteString(final PrintStream out, final String in) {
        final StringBuffer buf = new StringBuffer();
        buf.append("'");
        final int len = in.length();
        for (int i = 0; i < len; ++i) {
            final char c = in.charAt(i);
            if (c == '\'' || c == '\\') {
                buf.append("\\");
            }
            buf.append(c);
        }
        buf.append("'");
        out.print(buf.toString());
    }

    /**
     * skip whitespace. return false, if EOF reached.
     */
    private boolean skipWhite(final Reader in) throws IOException {
        in.mark(1);
        int c;
        while ((c = in.read()) > 0) {
            if (!Character.isWhitespace((char) c)) {
                in.reset();
                return true;
            }
            in.mark(1);
        }
        return false;
    }

    private String readToken(final LineNumberReader in) throws IOException {
        skipWhite(in);
        final StringBuffer token = new StringBuffer();
        in.mark(1);
        int c;
        while ((c = in.read()) > 0) {
            final char ch = (char) c;
            if (Character.isWhitespace(ch) || ch == ';' || ch == ','
                || ch == '(' || ch == ')') {
                in.reset();
                break;
            }
            token.append(ch);
            in.mark(1);
        }
        return token.toString();
    }

    /**
     * read a string. This is either NULL without quotes or a quoted string.
     */
    private String readString(final LineNumberReader in) throws IOException {
        int nullParseState = 0;
        int c;
        while ((c = in.read()) > 0) {
            final char ch = (char) c;
            // unless we already parse the NULL string, skip whitespaces.
            if (nullParseState == 0 && Character.isWhitespace(ch)) {
                continue;
            }
            if (ch == '\'') {
                break; // -> opening string.
            }
            if (Character.toUpperCase(ch) == NULL_STR.charAt(nullParseState)) {
                ++nullParseState;
                if (nullParseState == NULL_STR.length()) {
                    return null;
                }
                continue;
            }
            raiseException(in, "unecpected character '" + ch + "'");
        }

        // ok, we found an opening quote.
        final StringBuffer result = new StringBuffer();
        while ((c = in.read()) > 0) {
            if (c == '\\') {
                c = in.read();
                if (c < 0) {
                    raiseException(in,
                    "excpected character after backslash escape");
                }
                result.append((char) c);
                continue;
            }
            final char ch = (char) c;
            if (ch == '\'') {
                break; // End Of String.
            }
            result.append((char) c);
        }
        return result.toString();
    }

    /**
     * convenience method to throw Exceptions containing the line number
     */
    private void raiseException(final LineNumberReader in, final String msg)
    throws IOException {
        throw new IOException("line " + (in.getLineNumber() + 1) + ": " + msg);
    }

    private void printMetaDataInfo(final MetaProperty[] prop) {
        HenPlus.out().println();
        META_HEADERS[0].resetWidth();
        META_HEADERS[1].resetWidth();
        final TableRenderer table = new TableRenderer(META_HEADERS, HenPlus.out());
        for (int i = 0; i < prop.length; ++i) {
            final Column[] row = new Column[3];
            row[0] = new Column(prop[i].getFieldName());
            row[1] = new Column(prop[i].getTypeName());
            row[2] = new Column(prop[i].getMaxLength());
            table.addRow(row);
        }
        table.closeTable();
    }

    // -- Interruptable interface
    public synchronized void interrupt() {
        _running = false;
    }

    private void beginInterruptableSection() {
        _running = true;
        SigIntHandler.getInstance().pushInterruptable(this);
    }

    private void endInterruptableSection() {
        SigIntHandler.getInstance().popInterruptable();
    }

    /**
     * complete the table name.
     */
    @Override
    public Iterator complete(final CommandDispatcher disp, final String partialCommand,
            String lastWord) {
        final StringTokenizer st = new StringTokenizer(partialCommand);
        final String cmd = (String) st.nextElement();
        int argc = st.countTokens();
        if (lastWord.length() > 0) {
            argc--;
        }

        if ("dump-conditional".equals(cmd)) {
            if (argc == 0) {
                return new FileCompletionIterator(partialCommand, lastWord);
            } else if (argc == 1) {
                if (lastWord.startsWith("\"")) {
                    lastWord = lastWord.substring(1);
                }
                return _tableCompleter.completeTableName(HenPlus.getInstance()
                        .getCurrentSession(), lastWord);
            } else if (argc > 1) {
                st.nextElement(); // discard filename.
                final String table = (String) st.nextElement();
                final Collection columns = _tableCompleter.columnsFor(table);
                final NameCompleter compl = new NameCompleter(columns);
                return compl.getAlternatives(lastWord);
            }
        } else if ("dump-out".equals(cmd)) {
            // this is true for dump-out und verify-dump
            if (argc == 0) {
                return new FileCompletionIterator(partialCommand, lastWord);
            }
            if (argc > 0) {
                if (lastWord.startsWith("\"")) {
                    lastWord = lastWord.substring(1);
                }
                final HashSet alreadyGiven = new HashSet();
                /*
                 * do not complete the tables we already gave on the
                 * commandline.
                 */
                while (st.hasMoreElements()) {
                    alreadyGiven.add(st.nextElement());
                }

                final Iterator it = _tableCompleter.completeTableName(HenPlus
                        .getInstance().getCurrentSession(), lastWord);
                return new Iterator() {
                    String table = null;

                    public boolean hasNext() {
                        while (it.hasNext()) {
                            table = (String) it.next();
                            if (alreadyGiven.contains(table)) {
                                continue;
                            }
                            return true;
                        }
                        return false;
                    }

                    public Object next() {
                        return table;
                    }

                    public void remove() {
                        throw new UnsupportedOperationException("no!");
                    }
                };
            }
        } else {
            if (argc == 0) {
                return new FileCompletionIterator(partialCommand, lastWord);
            }
        }
        return null;
    }

    /**
     * return a descriptive string.
     */
    @Override
    public String getShortDescription() {
        return "handle table dumps";
    }

    @Override
    public String getSynopsis(final String cmd) {
        if ("dump-out".equals(cmd)) {
            return cmd + " <filename> (<tablename> | <prefix>* | *)+;";
        } else if ("dump-conditional".equals(cmd)) {
            return cmd + " <filename> <tablename> [<where-clause>]";
        } else if ("dump-select".equals(cmd)) {
            return cmd + " <filename> <exported-tablename> select ...";
        } else if ("dump-in".equals(cmd)) {
            return cmd + " <filename> [<commit-intervall>]";
        } else if ("verify-dump".equals(cmd)) {
            return cmd + " <filename>";
        }
        return cmd;
    }

    @Override
    public String getLongDescription(final String cmd) {
        String dsc = null;
        if ("dump-out".equals(cmd)) {
            dsc = "\tDump out the contents of the table(s) given to the file\n"
                + "\twith the given name. If the filename ends with '.gz', the\n"
                + "\tcontent is gzip'ed automatically .. that saves space.\n"
                + "\n"
                + "\tFor the selection of the tables you want to dump-out,\n"
                + "\tyou are able to use wildcards (*) to match all tables or\n"
                + "\ta specific set of tables.\n"
                + "\tE.g. you might specify \"*\" to match all tables, or\"tb_*\"\n"
                + "\tto match all tables starting with \"tb_\".\n"
                + "\n"
                + "\tThe dump-format allows to read in the data back into\n"
                + "\tthe database ('dump-in' command). And unlike pure SQL-insert\n"
                + "\tstatements, this works even across databases.\n"
                + "\tSo you can make a dump of your MySQL database and read it\n"
                + "\tback into Oracle, for instance. To achive this database\n"
                + "\tindependence, the data is stored in a canonical form\n"
                + "\t(e.g. the Time/Date). The file itself is human readable\n"
                + "\tand uses less space than simple SQL-inserts:\n"
                + "\t----------------\n"
                + "\t  (tabledump 'student'\n"
                + "\t    (dump-version 1 1)\n"
                + "\t    (henplus-version 0.3.3)\n"
                + "\t    (database-info 'MySQL - 3.23.47')\n"
                + "\t    (meta ('name',   'sex',    'student_id')\n"
                + "\t          ('STRING', 'STRING', 'INTEGER'   ))\n"
                + "\t    (data ('Megan','F',1)\n"
                + "\t          ('Joseph','M',2)\n"
                + "\t          ('Kyle','M',3)\n"
                + "\t          ('Mac Donald\\'s','M',4))\n"
                + "\t    (rows 4))\n"
                + "\t----------------\n\n"
                + "\tTODOs\n"
                + "\tThis format contains only the data, no\n"
                + "\tcanonical 'create table' statement - so the table must\n"
                + "\talready exist at import time. Both these features will\n"
                + "\tbe in later versions of HenPlus.";
        }

        else if ("dump-conditional".equals(cmd)) {
            dsc = "\tLike dump-out, but dump only the rows of a single table\n"
                + "\tthat match the where clause.";
        }

        else if ("dump-in".equals(cmd)) {
            dsc = "\tRead back in the data that has been dumped out with the\n"
                + "\t'dump-out' command. If the filename ends with '.gz',\n"
                + "\tthen the content is assumed to be gzipped and is\n"
                + "\tunpacked on the fly. The 'dump-in' command fills\n"
                + "\texisting tables, it does not create missing ones!\n\n"
                + "\tExisting content ist not deleted before, dump-in just\n"
                + "\tinserts all data found in the dump.\n\n"
                + "\tInternally, the import uses a prepared statement that is\n"
                + "\tfed with the typed data according to the meta data (see\n"
                + "\tdump-out for the file format). This evens out differences\n"
                + "\tbetween databases and of course enhances speed compared\n"
                + "\tto non-prepared statements.\n\n"
                + "\tThe import is done in the current transaction, unless\n"
                + "\tyou specify the commit-interval. The commit-interval specify\n"
                + "\tthe number of inserts, that are executed before an commit\n"
                + "\tis done. For a large amount of data this option is\n"
                + "\tnecessary, since otherwise your rollback-segments\n"
                + "\tmight get a problem ;-)";
        }

        else if ("verify-dump".equals(cmd)) {
            dsc = "\tLike dump-in, but a 'dry run'. Won't change anything\n"
                + "\tbut parses the whole file to determine whether it has\n"
                + "\tsyntax errors or is damaged. Any syntax errors are\n"
                + "\treported as it were a 'dump-in'. Problems that might\n"
                + "\toccur in a 'real' import in the database (that might\n"
                + "\tdetect, that the import would create duplicate keys for\n"
                + "\tinstance) can not be determined, of course.";
        }
        return dsc;
    }

    /**
     * A source for dumps.
     */
    private interface DumpSource {
        MetaProperty[] getMetaProperties() throws SQLException;

        String getDescription();

        String getTableName();

        Statement getStatement() throws SQLException;

        ResultSet getResultSet() throws SQLException;

        long getExpectedRows();
    }

    private static class SelectDumpSource implements DumpSource {
        private final SQLSession _session;
        private final String _sqlStat;
        private final String _exportTable;
        private MetaProperty[] _meta;
        private Statement _workingStatement;
        private ResultSet _resultSet;

        SelectDumpSource(final SQLSession session, final String exportTable, final String sqlStat) {
            _session = session;
            _sqlStat = sqlStat;
            _exportTable = exportTable;
        }

        public MetaProperty[] getMetaProperties() throws SQLException {
            if (_meta != null) {
                return _meta;
            }
            final ResultSet rset = getResultSet();

            final ResultSetMetaData rsMeta = rset.getMetaData();
            final int cols = rsMeta.getColumnCount();
            _meta = new MetaProperty[cols];
            for (int i = 0; i < cols; ++i) {
                _meta[i] = new MetaProperty(rsMeta.getColumnName(i + 1), rsMeta
                        .getColumnType(i + 1));
            }
            return _meta;
        }

        public String getDescription() {
            return _sqlStat;
        }

        public String getTableName() {
            return _exportTable;
        }

        public Statement getStatement() throws SQLException {
            return _workingStatement;
        }

        public ResultSet getResultSet() throws SQLException {
            if (_resultSet != null) {
                return _resultSet;
            }
            _workingStatement = _session.createStatement();
            try {
                _workingStatement.setFetchSize(1000);
            } catch (final Exception e) {
                // ignore
            }
            _resultSet = _workingStatement.executeQuery(_sqlStat);
            return _resultSet;
        }

        public long getExpectedRows() {
            return -1;
        }
    }

    private static class TableDumpSource implements DumpSource {
        private final SQLSession _session;
        private final String _table;
        private final String _schema;
        private final boolean _caseSensitive;
        private MetaProperty[] _meta;
        private Statement _workingStatement;
        private String _whereClause;

        TableDumpSource(final String schema, final String table, final boolean caseSensitive,
                final SQLSession session) {
            _session = session;
            _schema = schema;
            _table = table;
            _caseSensitive = caseSensitive;
        }

        public String getDescription() {
            return "table '" + _table + "'";
        }

        public String getTableName() {
            return _table;
        }

        public void setWhereClause(final String whereClause) {
            _whereClause = whereClause;
        }

        public Statement getStatement() {
            return _workingStatement;
        }

        public MetaProperty[] getMetaProperties() throws SQLException {
            if (_meta != null) {
                return _meta;
            }

            final List<MetaProperty> metaList = new ArrayList<MetaProperty>();
            final Connection conn = _session.getConnection();
            ResultSet rset = null;
            try {
                /*
                 * if the same column is in more than one schema defined, then
                 * oracle seems to write them out twice..
                 */
                final Set doubleCheck = new HashSet();
                final DatabaseMetaData meta = conn.getMetaData();
                rset = meta
                .getColumns(conn.getCatalog(), _schema, _table, null);
                while (rset.next()) {
                    final String columnName = rset.getString(4);
                    if (doubleCheck.contains(columnName)) {
                        continue;
                    }
                    doubleCheck.add(columnName);
                    metaList.add(new MetaProperty(columnName, rset.getInt(5)));
                }
            } finally {
                if (rset != null) {
                    try {
                        rset.close();
                    } catch (final Exception e) {
                    }
                }
            }
            _meta = metaList.toArray(new MetaProperty[metaList.size()]);
            return _meta;
        }

        public ResultSet getResultSet() throws SQLException {
            final StringBuffer selectStmt = new StringBuffer("SELECT ");
            for (int i = 0; i < _meta.length; ++i) {
                final MetaProperty p = _meta[i];
                if (i != 0) {
                    selectStmt.append(", ");
                }
                selectStmt.append(p.fieldName);
            }

            selectStmt.append(" FROM ").append(_table);
            if (_whereClause != null) {
                selectStmt.append(" WHERE ").append(_whereClause);
            }
            _workingStatement = _session.createStatement();
            try {
                _workingStatement.setFetchSize(1000);
            } catch (final Exception e) {
                // ignore
            }
            return _workingStatement.executeQuery(selectStmt.toString());
        }

        public long getExpectedRows() {
            final CancelWriter selectInfo = new CancelWriter(HenPlus.msg());
            Statement stmt = null;
            ResultSet rset = null;
            try {
                selectInfo.print("determining number of rows...");
                stmt = _session.createStatement();
                final StringBuffer countStmt = new StringBuffer(
                "SELECT count(*) from ");
                countStmt.append(_table);
                if (_whereClause != null) {
                    countStmt.append(" WHERE ");
                    countStmt.append(_whereClause);
                }
                rset = stmt.executeQuery(countStmt.toString());
                rset.next();
                return rset.getLong(1);
            } catch (final Exception e) {
                return -1;
            } finally {
                if (rset != null) {
                    try {
                        rset.close();
                    } catch (final Exception e) {
                    }
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (final Exception e) {
                    }
                }
                selectInfo.cancel();
            }
        }
    }

    private static class MetaProperty {
        private int _maxLen;
        public final String fieldName;
        public int type;
        public String typeName;

        public MetaProperty(final String fieldName) {
            this.fieldName = fieldName;
            _maxLen = -1;
        }

        public MetaProperty(final String fieldName, final int jdbcType) {
            this.fieldName = fieldName;
            this.typeName = JDBCTYPE2TYPENAME.get(new Integer(jdbcType));
            if (this.typeName == null) {
                HenPlus.msg().println(
                        "cannot handle type '" + type + "' for field '"
                        + this.fieldName + "'; trying String..");
                this.type = HP_STRING;
                this.typeName = TYPES[this.type];
            } else {
                this.type = findType(typeName);
            }
            _maxLen = -1;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getTypeName() {
            return typeName;
        }

        public void setTypeName(final String typeName) {
            this.type = findType(typeName);
            this.typeName = typeName;
        }

        public void updateMaxLength(final String val) {
            if (val != null) {
                updateMaxLength(val.length());
            }
        }

        public void updateMaxLength(final int maxLen) {
            if (maxLen > this._maxLen) {
                this._maxLen = maxLen;
            }
        }

        public int getMaxLength() {
            return this._maxLen;
        }

        /**
         * find the type in the array. uses linear search, but this is only a
         * small list.
         */
        private int findType(String typeName) {
            if (typeName == null) {
                throw new IllegalArgumentException("empty type ?");
            }
            typeName = typeName.toUpperCase();
            for (int i = 0; i < TYPES.length; ++i) {
                if (TYPES[i].equals(typeName)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("invalid type " + typeName);
        }

        public int getType() {
            return type;
        }

        public int renderWidth() {
            return Math.max(typeName.length(), fieldName.length());
        }
    }

    private static class EncodingMismatchException extends IOException {
        private static final long serialVersionUID = 1;
        private final String _encoding;

        public EncodingMismatchException(final String encoding) {
            super("file encoding Mismatch Exception; got " + encoding);
            _encoding = encoding;
        }

        public String getEncoding() {
            return _encoding;
        }
    }

    // reading BLOBs.
    // private static class Base64InputStream extends InputStream { }

    // reading CLOBs
    // private static class Base64Reader extends Reader { }
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
