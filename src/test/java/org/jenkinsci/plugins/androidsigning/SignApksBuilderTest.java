package org.jenkinsci.plugins.androidsigning;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.CertificateCredentialsImpl;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matchers;
import org.jenkinsci.remoting.RoleChecker;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.jvnet.hudson.test.FakeLauncher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.PretendSlave;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyStoreException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.AbstractProject;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Label;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.security.ACL;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.MasterToSlaveFileCallable;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildWrapper;
import jenkins.util.VirtualFile;

import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.sameInstance;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;


public class SignApksBuilderTest {

    private static final String KEY_STORE_ID = SignApksBuilderTest.class.getSimpleName() + ".keyStore";

    private static class CopyTestWorkspace extends SimpleBuildWrapper {

        private FilePath sourceDir;

        public CopyTestWorkspace(FilePath sourceDir) {
            this.sourceDir = sourceDir;
        }

        @Override
        public void setUp(Context context, Run<?, ?> build, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars initialEnvironment) throws IOException, InterruptedException {
            sourceDir.copyRecursiveTo("*/**", workspace);
        }

        @Extension
        public static class DescriptorImpl extends BuildWrapperDescriptor {
            @Override
            public boolean isApplicable(AbstractProject<?, ?> item) {
                return true;
            }
            @Override
            public String getDisplayName() {
                return getClass().getSimpleName();
            }
        }

    }

    private static class CopyFileCallable extends MasterToSlaveFileCallable<Void> {

        private static final long serialVersionUID = 1L;

        private final String destPath;

        private CopyFileCallable(String destPath) {
            this.destPath = destPath;
        }

        @Override
        public Void invoke(File f, VirtualChannel channel) throws IOException, InterruptedException {
            long fileSize = f.length();
            FileChannel inChannel = FileChannel.open(f.toPath(), StandardOpenOption.READ);
            FileChannel outChannel = FileChannel.open(new File(destPath).toPath(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC);
            inChannel.transferTo(0, fileSize, outChannel);
            outChannel.close();
            inChannel.close();
            System.out.printf("%s copied %s to %s", getClass().getSimpleName(), f.getAbsolutePath(), destPath);
            return null;
        }
    }

    private static class FakeZipalign implements FakeLauncher {

        private Launcher.ProcStarter lastProc;

        @Override
        public Proc onLaunch(Launcher.ProcStarter p) throws IOException {
            lastProc = p;
            PrintStream logger = new PrintStream(p.stdout());
            List<String> cmd = p.cmds();
            String inPath = cmd.get(cmd.size() - 2);
            String outPath = cmd.get(cmd.size() - 1);
            FilePath workspace = p.pwd();
            FilePath in = workspace.child(inPath);
            FilePath out = workspace.child(outPath);
            try {
                out.getParent().mkdirs();
                if (!out.getParent().isDirectory()) {
                    throw new IOException("destination directory does not exist: " + out.getParent());
                }
                logger.printf("FakeZipalign copy %s to %s in pwd %s%n", in.getRemote(), out.getRemote(), workspace);
                System.setProperty("sun.io.serialization.extendedDebugInfo", "true");
                in.act(new CopyFileCallable(out.getRemote()));
                // TODO: this was resulting in incomplete copies and failing tests, for some reason
                // sometimes the output file would not have been completely written and reading the
                // aligned apk was failing
                // in.copyTo(out);
                logger.printf("FakeZipalign copy complete%n");
                if (!out.exists()) {
                    throw new IOException("FakeZipalign copy output does not exist: " + out.getRemote());
                }
                long outSize = out.length(), inSize = in.length();
                if (outSize != inSize) {
                    throw new IOException("FakeZipalign copy output size " + outSize + " is different from input size " + inSize);
                }
                return new FakeLauncher.FinishedProc(0);
            }
            catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class BuildArtifact {
        private final FreeStyleBuild build;
        private final Run.Artifact artifact;
        private BuildArtifact(FreeStyleBuild build, Run.Artifact artifact) {
            this.build = build;
            this.artifact = artifact;
        }
    }

    private static class ApkArtifactIsSignedMatcher extends BaseMatcher<BuildArtifact> {
        private final StandardCertificateCredentials signer;
        private final X509Certificate expectedCert;
        private final StringBuilder descText = new StringBuilder();
        private ApkArtifactIsSignedMatcher(Apk signingEntry) throws KeyStoreException {
            List<StandardCertificateCredentials> result = CredentialsProvider.lookupCredentials(
                StandardCertificateCredentials.class, Jenkins.getInstance(), ACL.SYSTEM, Collections.emptyList());
            signer = CredentialsMatchers.firstOrNull(result, CredentialsMatchers.withId(KEY_STORE_ID));
            expectedCert = (X509Certificate) signer.getKeyStore().getCertificate(signingEntry.getAlias());
        }
        @Override
        public boolean matches(Object item) {
            BuildArtifact actual = (BuildArtifact)item;
            descText.append(actual.artifact.getFileName());
            try {
                VirtualFile virtualSignedApk = actual.build.getArtifactManager().root().child(actual.artifact.relativePath);
                FilePath signedApkPath = actual.build.getWorkspace().createTempFile(actual.artifact.getFileName().replace(".apk", ""), ".apk");
                signedApkPath.copyFrom(virtualSignedApk.open());
                VerifyApkCallable.VerifyResult result = signedApkPath.act(new VerifyApkCallable(TaskListener.NULL));
                if (!result.isVerified) {
                    descText.append(" not verified;");
                }
                if (!result.isVerifiedV2Scheme) {
                    descText.append(" not verified v2;");
                }
                if (!result.isVerifiedV1Scheme) {
                    descText.append(" not verified v1;");
                }
                if (result.certs.length != 1) {
                    descText.append(" signer cert chain length should be 1, was ").append(result.certs.length);
                }
                else if (!result.certs[0].equals(expectedCert)) {
                    descText.append(" signer cert differs from expected cert");
                }
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }

            return descText.length() == actual.artifact.getFileName().length();
        }
        @Override
        public void describeTo(Description description) {
            description.appendText(descText.toString());
        }
    }

    private static BuildArtifact buildArtifact(FreeStyleBuild build, Run.Artifact artifact) {
        return new BuildArtifact(build, artifact);
    }

    private static ApkArtifactIsSignedMatcher isSignedWith(Apk signingEntry) throws KeyStoreException {
        return new ApkArtifactIsSignedMatcher(signingEntry);
    }

    @Rule
    public JenkinsRule testJenkins = new JenkinsRule();

    @Rule
    public TestName currentTestName = new TestName();

    private StandardCertificateCredentials credentials = null;
    private FilePath sourceWorkspace = null;
    private FilePath androidHome = null;
    private FakeZipalign zipalignLauncer = null;

    @Before
    public void addCredentials() {
        try {
            InputStream keyStoreIn = getClass().getResourceAsStream("/" + getClass().getSimpleName() + ".p12");
            byte[] keyStoreBytes = new byte[keyStoreIn.available()];
            keyStoreIn.read(keyStoreBytes);
            String keyStore = new String(Base64.getEncoder().encode(keyStoreBytes), "utf-8");
            credentials = new CertificateCredentialsImpl(
                CredentialsScope.GLOBAL, KEY_STORE_ID, "", getClass().getSimpleName(),
                new CertificateCredentialsImpl.UploadedKeyStoreSource(keyStore));
            CredentialsStore store = CredentialsProvider.lookupStores(testJenkins.jenkins).iterator().next();
            store.addCredentials(Domain.global(), credentials);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setupEnvironment() throws Exception {
        EnvironmentVariablesNodeProperty prop = new EnvironmentVariablesNodeProperty();
        EnvVars envVars = prop.getEnvVars();
        URL androidHomeUrl = getClass().getResource("/android");
        String androidHomePath = androidHomeUrl.getPath();
        envVars.put("ANDROID_HOME", androidHomePath);
        testJenkins.jenkins.getGlobalNodeProperties().add(prop);
        androidHome = new FilePath(new File(androidHomeUrl.toURI()));

        URL workspaceUrl = getClass().getResource("/workspace");
        sourceWorkspace = new FilePath(new File(workspaceUrl.toURI()));

        zipalignLauncer = new FakeZipalign();
        PretendSlave slave = testJenkins.createPretendSlave(zipalignLauncer);
        slave.setLabelString(slave.getLabelString() + " " + getClass().getSimpleName());
    }

    @After
    public void removeCredentials() {
        CredentialsStore store = CredentialsProvider.lookupStores(testJenkins.jenkins).iterator().next();
        try {
            store.removeCredentials(Domain.global(), credentials);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
        credentials = null;
    }

    private FreeStyleProject createSignApkJob() throws IOException {
        FreeStyleProject job = testJenkins.createFreeStyleProject(currentTestName.getMethodName());
        job.getBuildWrappersList().add(new CopyTestWorkspace(sourceWorkspace));
        job.setAssignedLabel(Label.get(getClass().getSimpleName()));
        return job;
    }

    @Test
    public void credentailsExist() {
        List<StandardCertificateCredentials> result = CredentialsProvider.lookupCredentials(
            StandardCertificateCredentials.class, testJenkins.jenkins, ACL.SYSTEM, Collections.emptyList());
        StandardCertificateCredentials credentials = CredentialsMatchers.firstOrNull(result, CredentialsMatchers.withId(KEY_STORE_ID));
        assertThat(credentials, sameInstance(this.credentials));
        try {
            assertTrue(credentials.getKeyStore().containsAlias(getClass().getSimpleName()));
        }
        catch (KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void archivesTheSignedApk() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(false));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts.size(), equalTo(1));
        Run.Artifact signedApkArtifact = artifacts.get(0);
        assertThat(signedApkArtifact.getFileName(), equalTo("SignApksBuilderTest-signed.apk"));
    }

    @Test
    public void archivesTheUnsignedApk() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(false).archiveUnsignedApk(true));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts.size(), equalTo(1));
        Run.Artifact signedApkArtifact = artifacts.get(0);
        assertThat(signedApkArtifact.getFileName(), equalTo("SignApksBuilderTest-unsigned.apk"));
    }

    @Test
    public void archivesTheUnsignedAndSignedApks() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(true));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts.size(), equalTo(2));
        Run.Artifact signedApkArtifact = artifacts.get(0);
        Run.Artifact unsignedApkArtifact = artifacts.get(1);
        assertThat(signedApkArtifact.getFileName(), equalTo("SignApksBuilderTest-signed.apk"));
        assertThat(unsignedApkArtifact.getFileName(), equalTo("SignApksBuilderTest-unsigned.apk"));
    }

    @Test
    public void archivesNothing() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(false).archiveUnsignedApk(false));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts, empty());
    }

    @Test
    public void signsTheApk() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(false));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();
        Run.Artifact signedApkArtifact = artifacts.get(0);

        assertThat(buildArtifact(build, signedApkArtifact), isSignedWith(entries.get(0)));
    }

    @Test
    public void supportsApksWithoutUnsignedSuffix() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "SignApksBuilderTest.apk")
            .archiveSignedApks(true).archiveUnsignedApk(true));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        Run.Artifact signedApkArtifact = artifacts.get(0);
        Run.Artifact unsignedApkArtifact = artifacts.get(1);

        assertThat(buildArtifact(build, signedApkArtifact), isSignedWith(entries.get(0)));
        assertThat(signedApkArtifact.getFileName(), equalTo("SignApksBuilderTest-signed.apk"));
        assertThat(unsignedApkArtifact.getFileName(), equalTo("SignApksBuilderTest.apk"));
    }

    @Test
    public void signsAllMatchingApks() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "SignApksBuilderTest-*.apk")
            .archiveSignedApks(true).archiveUnsignedApk(true));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts.size(), equalTo(4));
        assertThat(artifacts, hasItems(
            hasProperty("fileName", endsWith("SignApksBuilderTest-chocolate_flavor.apk")),
            hasProperty("fileName", endsWith("SignApksBuilderTest-chocolate_flavor-signed.apk")),
            hasProperty("fileName", endsWith("SignApksBuilderTest-unsigned.apk")),
            hasProperty("fileName", endsWith("SignApksBuilderTest-signed.apk"))));

        //noinspection Duplicates
        artifacts.forEach(artifact -> {
            try {
                if (!artifact.getFileName().endsWith("-signed.apk")) {
                    return;
                }
                assertThat(buildArtifact(build, artifact), isSignedWith(entries.get(0)));
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void signsMultipleApksThatWillHaveConflictingSignedFileNames() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "SignApksBuilderTest.apk, SignApksBuilderTest-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(true));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        FreeStyleBuild build = testJenkins.buildAndAssertSuccess(job);
        List<Run<FreeStyleProject,FreeStyleBuild>.Artifact> artifacts = build.getArtifacts();

        assertThat(artifacts.size(), equalTo(4));

        List<String> relPaths = artifacts.stream().map(artifact -> artifact.relativePath).collect(Collectors.toList());
        assertThat(relPaths, hasItems(
            "SignApksBuilder/" + KEY_STORE_ID + "-1/SignApksBuilderTest.apk",
            "SignApksBuilder/" + KEY_STORE_ID + "-1/SignApksBuilderTest-signed.apk",
            "SignApksBuilder/" + KEY_STORE_ID + "-2/SignApksBuilderTest-unsigned.apk",
            "SignApksBuilder/" + KEY_STORE_ID + "-2/SignApksBuilderTest-signed.apk"));

        FilePath[] workApks = build.getWorkspace().list("SignApksBuilder/" + KEY_STORE_ID + "-*/**/*.apk");
        assertThat(workApks.length, equalTo(4));

        //noinspection Duplicates
        artifacts.forEach(artifact -> {
            try {
                if (!artifact.getFileName().endsWith("-signed.apk")) {
                    return;
                }
                assertThat(buildArtifact(build, artifact), isSignedWith(entries.get(0)));
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    public void supportsMultipleApkGlobs() throws Exception {

    }

    @Test
    public void usesAndroidHomeOverride() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(false));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FilePath androidHomeOverride = testJenkins.jenkins.getRootPath().createTempDir("android-home-override", null);
        androidHome.copyRecursiveTo(androidHomeOverride);
        builder.setAndroidHome(androidHomeOverride.getRemote());
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        testJenkins.buildAndAssertSuccess(job);

        assertThat(zipalignLauncer.lastProc.cmds().get(0), startsWith(androidHomeOverride.getRemote()));
    }

    @Test
    public void usesZipalignPathOverride() throws Exception {
        List<Apk> entries = new ArrayList<>();
        entries.add(new Apk(KEY_STORE_ID, getClass().getSimpleName(), "*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(false));
        SignApksBuilder builder = new SignApksBuilder(entries);
        FilePath zipalignOverride = testJenkins.jenkins.getRootPath().createTempDir("zipalign-override", null);
        zipalignOverride = zipalignOverride.createTextTempFile("zipalign-override", ".sh", "echo \"zipalign $@\"");
        builder.setZipalignPath(zipalignOverride.getRemote());
        FreeStyleProject job = createSignApkJob();
        job.getBuildersList().add(builder);
        testJenkins.buildAndAssertSuccess(job);

        assertThat(zipalignLauncer.lastProc.cmds().get(0), startsWith(zipalignOverride.getRemote()));
    }

    @Test
    public void identitySubmission() throws Exception {
        Apk entry = new Apk(KEY_STORE_ID, getClass().getSimpleName(), "**/*-unsigned.apk")
            .archiveSignedApks(true).archiveUnsignedApk(false);
        SignApksBuilder original = new SignApksBuilder(Arrays.asList(entry));
        FreeStyleProject job = testJenkins.createFreeStyleProject();
        job.getBuildersList().add(original);

        JenkinsRule.WebClient browser = testJenkins.createWebClient();
        HtmlPage configPage = browser.getPage(job, "configure");
        HtmlForm form = configPage.getFormByName("config");
        testJenkins.submit(form);
        SignApksBuilder submitted = (SignApksBuilder) job.getBuildersList().get(0);

        assertThat(original.getEntries().size(), Matchers.equalTo(1));
        assertThat(submitted.getEntries().size(), Matchers.equalTo(1));
        testJenkins.assertEqualBeans(submitted.getEntries().get(0), original.getEntries().get(0), "keyStore,alias,apksToSign,archiveUnsignedApks,archiveSignedApks");
    }

}
