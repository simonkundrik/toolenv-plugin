package hudson.plugins.toolenv;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.FreeStyleProject;
import hudson.tasks.Maven;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlCheckBoxInput;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.htmlunit.html.HtmlTextInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ToolEnvConfigTest {

    private JenkinsRule r;

    @BeforeEach
    void setUp(JenkinsRule r) {
        this.r = r;
    }

    @Test
    void onlyTheSelectedToolsAreTicked() throws Exception {
        tools("ant", "my-ant");
        FreeStyleProject p = project("MY_ANT_HOME");

        Map<String, Boolean> boxes = checkboxes(configure(p));

        // ANT_HOME is not selected, even though its name is part of the selected MY_ANT_HOME
        assertThat(boxes, hasEntry("MY_ANT_HOME", true));
        assertThat(boxes, hasEntry("ANT_HOME", false));
    }

    @Test
    void nothingIsTickedForAJobThatDoesNotUseTheWrapperYet() throws Exception {
        tools("ant", "my-ant");
        FreeStyleProject p = r.createFreeStyleProject();

        Map<String, Boolean> boxes = checkboxes(configure(p));

        assertThat(boxes, hasEntry("ANT_HOME", false));
        assertThat(boxes, hasEntry("MY_ANT_HOME", false));
    }

    @Test
    void aToolThatWasRemovedIsShownAndCanBeUnticked() throws Exception {
        tools("my-ant");
        FreeStyleProject p = project("GONE_HOME,MY_ANT_HOME");

        HtmlPage page = configure(p);

        assertThat(checkboxes(page), hasEntry("GONE_HOME", true));
        assertThat(checkboxes(page), hasEntry("MY_ANT_HOME", true));

        checkbox(page, "GONE_HOME").click();
        r.submit(page.getFormByName("config"));

        assertThat(p.getBuildWrappersList().get(ToolEnvBuildWrapper.class).vars, is("MY_ANT_HOME"));
    }

    @Test
    void selectionIsEditedByWholeVariableNames() throws Exception {
        // A_HOME is the start of A_HOME_B_HOME
        tools("x", "a_home_b", "a");
        FreeStyleProject p = project("X_HOME,A_HOME_B_HOME,A_HOME");

        HtmlPage page = configure(p);
        checkbox(page, "A_HOME").click();
        HtmlForm form = page.getFormByName("config");
        r.submit(form);

        assertThat(p.getBuildWrappersList().get(ToolEnvBuildWrapper.class).vars, is("X_HOME,A_HOME_B_HOME"));
    }

    @Test
    void tickingAToolAddsItToTheSelection() throws Exception {
        tools("x", "y");
        FreeStyleProject p = project("X_HOME");

        HtmlPage page = configure(p);
        checkbox(page, "Y_HOME").click();
        assertThat(((HtmlInput) page.getElementById("vars.field")).getValue(), is("X_HOME,Y_HOME"));
        r.submit(page.getFormByName("config"));

        assertThat(p.getBuildWrappersList().get(ToolEnvBuildWrapper.class).vars, is("X_HOME,Y_HOME"));
    }

    @Test
    void customNameIsShownNextToItsTool() throws Exception {
        tools("ant", "my-ant");
        FreeStyleProject p = project("MY_TOOL=MY_ANT_HOME");

        HtmlPage page = configure(p);

        assertThat(checkboxes(page), hasEntry("MY_ANT_HOME", true));
        assertThat(checkboxes(page), hasEntry("ANT_HOME", false));
        assertThat(customNames(page), hasEntry("MY_ANT_HOME", "MY_TOOL"));
        assertThat(customNames(page), hasEntry("ANT_HOME", ""));
    }

    @Test
    void aCustomNameTypedInTheFormIsSaved() throws Exception {
        tools("ant", "my-ant");
        FreeStyleProject p = project("ANT_HOME");

        HtmlPage page = configure(p);
        checkbox(page, "MY_ANT_HOME").click();
        nameField(page, "MY_ANT_HOME").type("MY_TOOL");
        assertThat(((HtmlInput) page.getElementById("vars.field")).getValue(), is("ANT_HOME,MY_TOOL=MY_ANT_HOME"));
        r.submit(page.getFormByName("config"));

        assertThat(vars(p), is("ANT_HOME,MY_TOOL=MY_ANT_HOME"));
    }

    @Test
    void changingACustomNameKeepsTheOrder() throws Exception {
        tools("a", "b", "c");
        FreeStyleProject p = project("A_HOME,TWO=B_HOME,C_HOME");

        HtmlPage page = configure(p);
        HtmlTextInput field = nameField(page, "B_HOME");
        field.setValue("");
        field.type("SECOND");
        r.submit(page.getFormByName("config"));

        assertThat(vars(p), is("A_HOME,SECOND=B_HOME,C_HOME"));
    }

    @Test
    void untickingAToolRemovesItsCustomNameToo() throws Exception {
        tools("ant", "my-ant");
        FreeStyleProject p = project("MY_TOOL=MY_ANT_HOME,ANT_HOME");

        HtmlPage page = configure(p);
        checkbox(page, "MY_ANT_HOME").click();
        r.submit(page.getFormByName("config"));

        assertThat(vars(p), is("ANT_HOME"));
    }

    @Test
    void customNameOfARemovedToolSurvivesSavingTheForm() throws Exception {
        tools("my-ant");
        FreeStyleProject p = project("OLD=GONE_HOME,MY_ANT_HOME");

        HtmlPage page = configure(p);
        assertThat(checkboxes(page), hasEntry("GONE_HOME", true));
        assertThat(customNames(page), hasEntry("GONE_HOME", "OLD"));
        r.submit(page.getFormByName("config"));

        assertThat(vars(p), is("OLD=GONE_HOME,MY_ANT_HOME"));
    }

    private static String vars(FreeStyleProject p) {
        return p.getBuildWrappersList().get(ToolEnvBuildWrapper.class).vars;
    }

    private static HtmlTextInput nameField(HtmlPage page, String var) {
        HtmlTextInput field = page.getFirstByXPath(
                "//*[contains(@class,'toolenv-row')][.//input[@data-v='" + var + "']]//input[@type='text']");
        assertThat(field, notNullValue());
        return field;
    }

    private static Map<String, String> customNames(HtmlPage page) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Object o : page.getByXPath("//*[contains(@class,'toolenv-row')]")) {
            DomElement row = (DomElement) o;
            HtmlCheckBoxInput box = row.getFirstByXPath(".//input[@type='checkbox']");
            HtmlTextInput field = row.getFirstByXPath(".//input[@type='text']");
            result.put(box.getAttribute("data-v"), field.getValue());
        }
        return result;
    }

    private void tools(String... names) {
        Maven.MavenInstallation[] installations = new Maven.MavenInstallation[names.length];
        for (int i = 0; i < names.length; i++) {
            installations[i] = new Maven.MavenInstallation(names[i], "/opt/" + names[i]);
        }
        r.jenkins.getDescriptorByType(Maven.MavenInstallation.DescriptorImpl.class).setInstallations(installations);
    }

    private FreeStyleProject project(String vars) throws IOException {
        FreeStyleProject p = r.createFreeStyleProject();
        p.getBuildWrappersList().add(new ToolEnvBuildWrapper(vars));
        return p;
    }

    private HtmlPage configure(FreeStyleProject p) throws Exception {
        return r.createWebClient().getPage(p, "configure");
    }

    private static HtmlCheckBoxInput checkbox(HtmlPage page, String var) {
        HtmlCheckBoxInput box = page.getFirstByXPath("//input[@type='checkbox'][@data-v='" + var + "']");
        assertThat(box, notNullValue());
        return box;
    }

    private static Map<String, Boolean> checkboxes(HtmlPage page) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Object o : page.getByXPath("//input[@type='checkbox'][@data-v]")) {
            HtmlCheckBoxInput box = (HtmlCheckBoxInput) o;
            result.put(box.getAttribute("data-v"), box.isChecked());
        }
        return result;
    }
}
