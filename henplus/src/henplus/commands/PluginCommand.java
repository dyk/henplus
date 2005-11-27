/*
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: PluginCommand.java,v 1.8 2005-11-27 16:20:28 hzeller Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus.commands;

import henplus.AbstractCommand;
import henplus.Command;
import henplus.CommandDispatcher;
import henplus.HenPlus;
import henplus.SQLSession;
import henplus.io.ConfigurationContainer;
import henplus.view.Column;
import henplus.view.ColumnMetaData;
import henplus.view.TableRenderer;
import henplus.view.util.SortedMatchIterator;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.StringTokenizer;
import java.util.TreeMap;

/**
 * A Command that handles Plugins.
 */
public final class PluginCommand extends AbstractCommand {
    private final static String PLUGINS_FILENAME = "plugins";
    private final static ColumnMetaData[] DRV_META;
    static {
	DRV_META = new ColumnMetaData[2];
	DRV_META[0] = new ColumnMetaData("plugin class");
	DRV_META[1] = new ColumnMetaData("commands");
    }

    private final SortedMap/*<ClassName-String,Command-Class>*/ _plugins;
    private final HenPlus   _henplus;
    private final ConfigurationContainer _config;
    
    /**
     * returns the command-strings this command can handle.
     */
    public String[] getCommandList() {
	return new String[] {
	    "list-plugins", "plug-in", "plug-out"
	};
    }
    
    public PluginCommand(HenPlus henplus) {
	_henplus = henplus;
	_plugins = new TreeMap();
        _config = henplus.createConfigurationContainer(PLUGINS_FILENAME);
    }

    /**
     * initial load of plugins.
     */
    public void load() {
        _config.read(new ConfigurationContainer.ReadAction() {
            public void readConfiguration(InputStream inStream) throws Exception {
                if (inStream == null) return;
                BufferedReader in = new BufferedReader(new InputStreamReader(inStream, "UTF-8"));
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.length() == 0) continue;
                    Command plugin = null;
                    try {
                        plugin = loadPlugin(line);
                    }
                    catch (Exception e) {
                        HenPlus.msg().println("couldn't load plugin '" + line + "' "
                                              + e.getMessage());
                    }
                    _plugins.put(line, plugin);
                }
                in.close();
            }
        });
    }
    
    public boolean requiresValidSession(String cmd) { return false; }

    /**
     * load a plugin and register it at HenPlus.
     */
    private Command loadPlugin(String className)
	throws ClassNotFoundException, ClassCastException,
	       InstantiationException, IllegalAccessException {
	Command plugin = null;
	Class pluginClass = Class.forName(className);
	plugin = (Command) pluginClass.newInstance();
	_henplus.getDispatcher().register(plugin);
	return plugin;
    }
    
    /**
     * execute the command given.
     */
    public int execute(SQLSession currentSession, String cmd, String param) {
	StringTokenizer st = new StringTokenizer(param);
	int argc = st.countTokens();
	
	if ("list-plugins".equals(cmd)) {
	    if (argc != 0) return SYNTAX_ERROR;
	    HenPlus.msg().println("loaded plugins are marked with '*'");
	    DRV_META[0].resetWidth();
	    DRV_META[1].resetWidth();
	    TableRenderer table = new TableRenderer(DRV_META, HenPlus.out());
	    Iterator it = _plugins.entrySet().iterator();
	    while (it.hasNext()) {
		Map.Entry entry = (Map.Entry) it.next();
		Column[] row = new Column[2];
		Command c = (Command) entry.getValue();
		String clsName = (String) entry.getKey();
		row[0] = new Column(((c != null) ? "* ":"  ")
				    + clsName);
		if (c != null) {
		    StringBuffer cmds = new StringBuffer();
		    String[] cmdList = c.getCommandList();
		    for (int i=0; i < cmdList.length; ++i) {
			cmds.append(cmdList[i]).append("\n");
		    }
		    row[1] = new Column( cmds.toString().trim() );
		}
		else {
		    row[1] = new Column( null );
		}
		table.addRow(row);
	    }
	    table.closeTable();
	    return SUCCESS;
	}
	else if ("plug-in".equals(cmd)) {
	    if (argc != 1) return SYNTAX_ERROR;
	    String pluginClass = (String) st.nextElement();
	    if (_plugins.containsKey(pluginClass)) {
		HenPlus.msg().println("plugin '" + pluginClass 
				   + "' already loaded");
		return EXEC_FAILED;
	    }
	    Command plugin = null;
	    try {
		plugin = loadPlugin(pluginClass);
	    }
	    catch (Exception e) {
		HenPlus.msg().println("couldn't load plugin: " + e.getMessage());
		return EXEC_FAILED;
	    }
	    if (plugin != null) {
		_plugins.put(pluginClass, plugin);
		String[] cmds = plugin.getCommandList();
		HenPlus.out().print("adding commands: ");
		for (int i=0; i < cmds.length; ++i) {
		    if (i!=0) HenPlus.out().print(", ");
		    HenPlus.out().print(cmds[i]);
		}
		HenPlus.out().println();
	    }
	}
	else if ("plug-out".equals(cmd)) {
	    if (argc != 1) return SYNTAX_ERROR;
	    String pluginClass = (String) st.nextElement();
	    if (!_plugins.containsKey(pluginClass)) {
		HenPlus.msg().println("unknown plugin '" + pluginClass + "'");
		return EXEC_FAILED;
	    }
	    else {
		Command c = (Command) _plugins.remove(pluginClass);
		_henplus.getDispatcher().unregister(c);
	    }
	}
	return SUCCESS;
    }
    
    public Iterator complete(CommandDispatcher disp,
			     String partialCommand, final String lastWord) 
    {
	StringTokenizer st = new StringTokenizer(partialCommand);
	String cmd = (String) st.nextElement();
	int argc = st.countTokens();
	// list-plugins gets no names.
	if ("list-plugins".equals(cmd)) 
	    return null;
	// do not complete beyond first word.
	if (argc > ("".equals(lastWord) ? 0 : 1)) {
		return null;
	}
        return new SortedMatchIterator(lastWord, _plugins);
    }

    public void shutdown() {
	_config.write(new ConfigurationContainer.WriteAction() {
            public void writeConfiguration(OutputStream outStream) throws Exception {
                PrintWriter out = new PrintWriter(new OutputStreamWriter(outStream, "UTF-8"));
                Iterator it = _plugins.keySet().iterator();
                while (it.hasNext()) {
                    out.println((String) it.next());
                }
                out.close();
            }
	});
    }
    
    /**
     * return a descriptive string.
     */
    public String getShortDescription() {
	return "handle Plugins";
    }

    public String getSynopsis(String cmd) {
	if ("list-plugins".equals(cmd)) return cmd;
	return cmd + " <plugin-class>";
    }

    public String getLongDescription(String cmd) {
	String dsc = null;
	if ("plug-in".equals(cmd)) {
	    dsc= "\tLoad plugin. This command takes as argument the name of\n"
		+"\tthe  class  that  implements  the  plugin, that is then\n"
		+"\tloaded from your classpath. The plugin then behaves like\n"
		+"\tany other built-in command (including help and completion).\n\n"
		+"\tWriting a  plugin is  simple: Just  write a  class that\n"
		+"\timplements the well documented henplus.Command interface.\n"
		+"\tYou can just simply derive from henplus.AbstractCommand\n"
		+"\tthat already implements the default behaviour. An example\n"
		+"\tof a plugin is the henplus.SamplePlugin that does nothing\n"
		+"\tbut shows how it works; try it:\n\n"
		+"\tplug-in henplus.SamplePlugin\n\n"
		+"\tOn exit of HenPlus, all names of the plugin-classes are\n"
		+"\tstored, so that  they are automatically  loaded on next\n"
		+"\tstartup.";
	}
	else if ("plug-out".equals(cmd)) {
	    dsc= "\tUnload plugin. Unload a previously loaded plugin. This\n"
		+"\tcommand provides completion for the class name.\n";
	}
	else if ("list-plugins".equals(cmd)) {
	    dsc= "\tList the plugins loaded. The plugins, that are actually\n"
		+"\tloaded have a little star (*) in the first column. If it\n"
		+"\tis not loaded, then you have to extend your CLASSPATH to\n"
		+"\taccess the plugins class.";
	}
	return dsc;
    }
}

/*
 * Local variables:
 * c-basic-offset: 4
 * compile-command: "ant -emacs -find build.xml"
 * End:
 */
