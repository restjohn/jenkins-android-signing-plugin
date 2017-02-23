package org.jenkinsci.plugins.androidsigning;

import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PretendSlave;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

import hudson.EnvVars;
import hudson.model.Run;
import hudson.slaves.EnvironmentVariablesNodeProperty;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.junit.Assert.assertThat;


public class SignApksStepTest {

    private JenkinsRule testJenkins = new JenkinsRule();
    private TestKeyStore testKeyStore = new TestKeyStore(testJenkins);

    @Rule
    public RuleChain jenkinsChain = RuleChain.outerRule(testJenkins).around(testKeyStore);

    private String androidHome;
    private PretendSlave slave;
    private FakeZipalign zipalign;

    @Before
    public void setupEnvironment() throws Exception {
        URL androidHomeUrl = getClass().getResource("/android");
        androidHome = new File(androidHomeUrl.toURI()).getAbsolutePath();
        EnvironmentVariablesNodeProperty prop = new EnvironmentVariablesNodeProperty();
        EnvVars envVars = prop.getEnvVars();
        envVars.put("ANDROID_HOME", androidHome);
        testJenkins.jenkins.getGlobalNodeProperties().add(prop);
        zipalign = new FakeZipalign();
        slave = testJenkins.createPretendSlave(zipalign);
        slave.getComputer().getEnvironment().put("ANDROID_HOME", androidHome);
        slave.setLabelString(slave.getLabelString() + " " + getClass().getSimpleName());
    }

    @Test
    public void dslWorks() throws Exception {
        // job setup
        WorkflowJob job = testJenkins.jenkins.createProject(WorkflowJob.class, getClass().getSimpleName());
        job.setDefinition(new CpsFlowDefinition(String.format(
            "node('%s') {%n" +
            "  wrap($class: 'CopyTestWorkspace') {%n" +
            "    signAndroidApks(" +
            "      keyStoreId: '%s',%n" +
            "      keyAlias: '%s',%n" +
            "      apksToSign: '**/*-unsigned.apk',%n" +
            "      archiveSignedApks: true,%n" +
            "      archiveUnsignedApks: true,%n" +
            "      androidHome: env.ANDROID_HOME%n" +
            "    )%n" +
            "  }%n" +
            "}", getClass().getSimpleName(), TestKeyStore.KEY_STORE_ID, TestKeyStore.KEY_ALIAS)));

        WorkflowRun build = testJenkins.buildAndAssertSuccess(job);
        List<String> artifactNames = build.getArtifacts().stream().map(Run.Artifact::getFileName).collect(Collectors.toList());

        assertThat(artifactNames.size(), equalTo(2));
        assertThat(artifactNames, hasItem(endsWith("SignApksBuilderTest-unsigned.apk")));
        assertThat(artifactNames, hasItem(endsWith("SignApksBuilderTest-signed.apk")));
        assertThat(zipalign.lastProc.cmds().get(0), startsWith(androidHome));
    }
        
}
