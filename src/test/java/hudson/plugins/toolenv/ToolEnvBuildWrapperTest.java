package hudson.plugins.toolenv;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeSpecific;
import hudson.tasks.Maven;
import hudson.tools.ToolDescriptor;
import hudson.tools.ToolInstallation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ToolEnvBuildWrapperTest {

    /** How many times Jenkins was asked to install a tool on a node, which runs its installers. */
    private static final AtomicInteger INSTALLS = new AtomicInteger();

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule r) {
        this.r = r;
        INSTALLS.set(0);
        r.jenkins
                .getDescriptorByType(CountingTool.DescriptorImpl.class)
                .setInstallations(
                        new CountingTool("counting", "/opt/counting", false),
                        new CountingTool("broken", "/opt/broken", true));
    }

    @Test
    void toolIsInstalledAndLoggedOncePerBuild() throws Exception {
        FreeStyleProject p = project("COUNTING_HOME");
        List<String> seen = readEnvironmentInSteps(p, 3, "COUNTING_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(seen, is(List.of("/opt/counting", "/opt/counting", "/opt/counting")));
        assertThat(INSTALLS.get(), is(1));
        assertThat(count(r.getLog(b), "Setting COUNTING_HOME=/opt/counting"), is(1));
    }

    @Test
    void environmentIsAvailableOnAnyThread() throws Exception {
        FreeStyleProject p = project("COUNTING_HOME");
        AtomicReference<String> seen = new AtomicReference<>();
        p.getBuildersList().add(new TestBuilder() {
            @Override
            public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                    throws InterruptedException, IOException {
                // Notifiers such as the Gerrit Trigger ask for the environment from a thread of their own,
                // which is not an executor thread.
                Thread t = new Thread(() -> {
                    try {
                        seen.set(build.getEnvironment(listener).get("COUNTING_HOME"));
                    } catch (IOException | InterruptedException x) {
                        x.printStackTrace(listener.error("Could not read the environment"));
                    }
                });
                t.start();
                t.join();
                return true;
            }
        });

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(seen.get(), is("/opt/counting"));
        assertThat(r.getLog(b), not(containsString("Computer is null")));
        assertThat(INSTALLS.get(), is(1));
    }

    @Test
    void missingToolIsReportedOnce() throws Exception {
        FreeStyleProject p = project("GONE_HOME");
        readEnvironmentInSteps(p, 3, "GONE_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(count(r.getLog(b), "No tool found matching GONE_HOME"), is(1));
    }

    @Test
    void failedInstallationIsReportedOnceAndDoesNotFailTheBuild() throws Exception {
        FreeStyleProject p = project("BROKEN_HOME,COUNTING_HOME");
        List<String> broken = readEnvironmentInSteps(p, 3, "BROKEN_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(broken.stream().allMatch(Objects::isNull), is(true));
        assertThat(count(r.getLog(b), "Could not install BROKEN_HOME"), is(1));
        assertThat(count(r.getLog(b), "Setting COUNTING_HOME=/opt/counting"), is(1));
    }

    @Test
    void homeThatDependsOnTheEnvironmentIsStillExpanded() throws Exception {
        r.jenkins
                .getGlobalNodeProperties()
                .add(new EnvironmentVariablesNodeProperty(
                        new EnvironmentVariablesNodeProperty.Entry("TOOLS_ROOT", "/tools")));
        r.jenkins
                .getDescriptorByType(Maven.MavenInstallation.DescriptorImpl.class)
                .setInstallations(new Maven.MavenInstallation("m3", "${TOOLS_ROOT}/maven"));
        FreeStyleProject p = project("M3_HOME");
        List<String> seen = readEnvironmentInSteps(p, 2, "M3_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(seen, is(List.of("/tools/maven", "/tools/maven")));
        assertThat(count(r.getLog(b), "Setting M3_HOME=/tools/maven"), is(1));
    }

    @Test
    void customNameIsSetInsteadOfTheDefaultOne() throws Exception {
        FreeStyleProject p = project("MY_TOOL=COUNTING_HOME");
        List<String> custom = readEnvironmentInSteps(p, 2, "MY_TOOL");
        List<String> original = readEnvironmentInSteps(p, 1, "COUNTING_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(custom, is(List.of("/opt/counting", "/opt/counting")));
        assertThat(original.stream().allMatch(Objects::isNull), is(true));
        assertThat(count(r.getLog(b), "Setting MY_TOOL=/opt/counting"), is(1));
        assertThat(INSTALLS.get(), is(1));
    }

    @Test
    void entriesWithAndWithoutACustomNameCanBeMixed() throws Exception {
        r.jenkins
                .getDescriptorByType(Maven.MavenInstallation.DescriptorImpl.class)
                .setInstallations(new Maven.MavenInstallation("m3", "/opt/m3"));
        FreeStyleProject p = project("COUNTING_HOME,M2_HOME=M3_HOME");
        List<String> counting = readEnvironmentInSteps(p, 1, "COUNTING_HOME");
        List<String> m2 = readEnvironmentInSteps(p, 1, "M2_HOME");
        List<String> m3 = readEnvironmentInSteps(p, 1, "M3_HOME");

        r.buildAndAssertSuccess(p);

        assertThat(counting, is(List.of("/opt/counting")));
        assertThat(m2, is(List.of("/opt/m3")));
        assertThat(m3.stream().allMatch(Objects::isNull), is(true));
    }

    @Test
    void invalidCustomNamesAreReportedOnceAndIgnored() throws Exception {
        FreeStyleProject p = project("1BAD=COUNTING_HOME,BAD NAME=COUNTING_HOME,COUNTING_HOME");
        List<String> seen = readEnvironmentInSteps(p, 3, "COUNTING_HOME");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        String log = r.getLog(b);
        assertThat(count(log, "Invalid variable name 1BAD"), is(1));
        assertThat(count(log, "Invalid variable name BAD NAME"), is(1));
        assertThat(seen, is(List.of("/opt/counting", "/opt/counting", "/opt/counting")));
        assertThat(INSTALLS.get(), is(1));
    }

    @Test
    void twoToolsCannotShareACustomName() throws Exception {
        r.jenkins
                .getDescriptorByType(Maven.MavenInstallation.DescriptorImpl.class)
                .setInstallations(new Maven.MavenInstallation("m3", "/opt/m3"));
        FreeStyleProject p = project("SHARED=COUNTING_HOME,SHARED=M3_HOME");
        List<String> seen = readEnvironmentInSteps(p, 2, "SHARED");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(seen, is(List.of("/opt/counting", "/opt/counting")));
        assertThat(count(r.getLog(b), "SHARED is already set by COUNTING_HOME, ignoring M3_HOME"), is(1));
    }

    @Test
    void missingToolWithACustomNameIsReportedByItsToolName() throws Exception {
        FreeStyleProject p = project("OLD=GONE_HOME");
        readEnvironmentInSteps(p, 2, "OLD");

        FreeStyleBuild b = r.buildAndAssertSuccess(p);

        assertThat(count(r.getLog(b), "No tool found matching GONE_HOME"), is(1));
    }

    private FreeStyleProject project(String vars) throws IOException {
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildWrappersList().add(new ToolEnvBuildWrapper(vars));
        return p;
    }

    /** Adds build steps that read one variable of the build environment, as shell or Maven steps do. */
    private static List<String> readEnvironmentInSteps(FreeStyleProject p, int steps, String var) throws IOException {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < steps; i++) {
            p.getBuildersList().add(new TestBuilder() {
                @Override
                public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
                        throws InterruptedException, IOException {
                    seen.add(build.getEnvironment(listener).get(var));
                    return true;
                }
            });
        }
        return seen;
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** A tool that counts its installations on a node, and can be told to fail to install. */
    public static class CountingTool extends ToolInstallation implements NodeSpecific<CountingTool> {

        private final boolean failToInstall;

        CountingTool(String name, String home, boolean failToInstall) {
            super(name, home, Collections.emptyList());
            this.failToInstall = failToInstall;
        }

        @Override
        public CountingTool forNode(Node node, TaskListener log) throws IOException {
            INSTALLS.incrementAndGet();
            if (failToInstall) {
                throw new IOException("cannot install " + getName());
            }
            return this;
        }

        @TestExtension
        public static class DescriptorImpl extends ToolDescriptor<CountingTool> {
            @Override
            public String getDisplayName() {
                return "Counting tool";
            }
        }
    }
}
