package hudson.plugins.toolenv;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.EnvironmentSpecific;
import hudson.model.Node;
import hudson.slaves.NodeSpecific;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.kohsuke.stapler.DataBoundConstructor;

public class ToolEnvBuildWrapper extends BuildWrapper {

    /** What a name the user chose for a variable has to look like. */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public final String vars;

    @DataBoundConstructor public ToolEnvBuildWrapper(String vars) {
        this.vars = vars;
    }

    public @Override Environment setUp(AbstractBuild build, Launcher launcher, final BuildListener listener) {
        // buildEnvVars is called again for every build step, and from threads that are not executor threads.
        // Finding the tools and installing them runs the tool installers, so do that once per build, here.
        Node node = build.getBuiltOn();
        final Map<String,ToolInstallation> tools = new LinkedHashMap<String,ToolInstallation>();
        Map<String,String> setBy = new HashMap<String,String>();
        for (Selection selection : selections()) {
            String var = selection.tool;
            ToolInstallation tool = toolsByVar().get(var);
            if (tool == null) {
                listener.error("No tool found matching " + var);
                continue;
            }
            String name = selection.name;
            if (!name.equals(var) && !VARIABLE_NAME.matcher(name).matches()) {
                listener.error("Invalid variable name " + name + " for " + var);
                continue;
            }
            String other = setBy.get(name);
            if (other != null) {
                if (!other.equals(var)) {
                    listener.error(name + " is already set by " + other + ", ignoring " + var);
                }
                continue;
            }
            if (tool instanceof NodeSpecific) {
                try {
                    if (node == null) {
                        listener.error("Could not install " + var + ". Node is null.");
                        continue;
                    }
                    tool = (ToolInstallation) ((NodeSpecific<?>) tool).forNode(node, listener);
                } catch (Exception x) {
                    x.printStackTrace(listener.error("Could not install " + var));
                    continue;
                }
            }
            setBy.put(name, var);
            tools.put(name, tool);
        }
        return new Environment() {
            private final Set<String> logged = ConcurrentHashMap.newKeySet();

            public @Override void buildEnvVars(Map<String,String> env) {
                for (Map.Entry<String,ToolInstallation> entry : tools.entrySet()) {
                    String var = entry.getKey();
                    ToolInstallation tool = entry.getValue();
                    if (tool instanceof EnvironmentSpecific) {
                        EnvVars e = new EnvVars(env);
                        tool = (ToolInstallation) ((EnvironmentSpecific<?>) tool).forEnvironment(e);
                    }
                    String home = tool.getHome();
                    if (logged.add(var)) {
                        listener.getLogger().println("Setting " + var + "=" + home);
                    }
                    env.put(var, home);
                }
            }
        };
    }

    private static Map<String,ToolInstallation> toolsByVar() {
        Map<String,ToolInstallation> r = new TreeMap<String,ToolInstallation>();
        for (ToolDescriptor<?> desc : ToolInstallation.all()) {
            for (ToolInstallation inst : desc.getInstallations()) {
                String instName = inst.getName();
                if (instName != null) {
                    // some installations return null instead of empty string
                    r.put(instName.replaceAll("[^a-zA-Z0-9_]+", "_").toUpperCase(Locale.ENGLISH) + "_HOME", inst);
                }
            }
        }
        return r;
    }

    public static Collection<String> availableVariableNames() {
        return toolsByVar().keySet();
    }

    /** Whether the tool with this variable is one of the selected ones. The form uses this to tick the checkboxes. */
    public boolean isSelected(String var) {
        for (Selection selection : selections()) {
            if (selection.tool.equals(var)) {
                return true;
            }
        }
        return false;
    }

    /** The name the user gave to the variable of this tool instead of the default one, or an empty string. */
    public String getCustomName(String var) {
        for (Selection selection : selections()) {
            if (selection.tool.equals(var) && !selection.name.equals(var)) {
                return selection.name;
            }
        }
        return "";
    }

    /** The selected variables that no tool matches any more, for example because the tool was removed. */
    public Collection<String> getMissingVariableNames() {
        Collection<String> available = availableVariableNames();
        Set<String> missing = new LinkedHashSet<String>();
        for (Selection selection : selections()) {
            if (!available.contains(selection.tool)) {
                missing.add(selection.tool);
            }
        }
        return missing;
    }

    /**
     * A selected tool: the variable it has by default, which is how the form knows it, and the name to set.
     * The name is the same unless the user gave another one, which is saved as {@code NAME=TOOL_HOME}.
     */
    private static final class Selection {
        final String tool;
        final String name;

        Selection(String tool, String name) {
            this.tool = tool;
            this.name = name;
        }
    }

    private List<Selection> selections() {
        List<Selection> r = new ArrayList<Selection>();
        for (String entry : Util.fixNull(vars).split(",")) {
            int eq = entry.indexOf('=');
            String tool = entry.substring(eq + 1);
            if (tool.isEmpty()) {
                continue;
            }
            String name = eq > 0 ? entry.substring(0, eq) : tool;
            r.add(new Selection(tool, name));
        }
        return r;
    }

    @Extension public static class Descriptor extends BuildWrapperDescriptor {

        public boolean isApplicable(AbstractProject item) {
            return true;
        }

        public @Override String getDisplayName() {
            return "Tool Environment";
        }

    }

}
