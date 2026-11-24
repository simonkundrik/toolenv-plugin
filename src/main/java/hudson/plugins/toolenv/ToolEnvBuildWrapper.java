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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.stapler.DataBoundConstructor;

public class ToolEnvBuildWrapper extends BuildWrapper {

    public final String vars;

    @DataBoundConstructor public ToolEnvBuildWrapper(String vars) {
        this.vars = vars;
    }

    public @Override Environment setUp(AbstractBuild build, Launcher launcher, final BuildListener listener) {
        // buildEnvVars is called again for every build step, and from threads that are not executor threads.
        // Finding the tools and installing them runs the tool installers, so do that once per build, here.
        Node node = build.getBuiltOn();
        final Map<String,ToolInstallation> tools = new LinkedHashMap<String,ToolInstallation>();
        for (String var : selectedVars()) {
            ToolInstallation tool = toolsByVar().get(var);
            if (tool == null) {
                listener.error("No tool found matching " + var);
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
            tools.put(var, tool);
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

    /** Whether the variable is one of the selected ones. The form uses this to tick the checkboxes. */
    public boolean isSelected(String var) {
        return selectedVars().contains(var);
    }

    /** The selected variables that no tool matches any more, for example because the tool was removed. */
    public Collection<String> getMissingVariableNames() {
        List<String> missing = new ArrayList<String>(selectedVars());
        missing.removeAll(availableVariableNames());
        return missing;
    }

    private List<String> selectedVars() {
        List<String> r = new ArrayList<String>();
        for (String var : Util.fixNull(vars).split(",")) {
            if (!var.isEmpty()) {
                r.add(var);
            }
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
