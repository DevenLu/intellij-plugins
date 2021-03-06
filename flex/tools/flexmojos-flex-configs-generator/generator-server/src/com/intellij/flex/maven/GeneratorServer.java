package com.intellij.flex.maven;

import org.apache.maven.DefaultMaven;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.execution.*;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.rtinfo.RuntimeInformation;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.codehaus.plexus.*;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.impl.LocalRepositoryProvider;
import org.eclipse.aether.internal.impl.DefaultLocalRepositoryProvider;
import org.eclipse.aether.internal.impl.DefaultRepositorySystem;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.spi.localrepo.LocalRepositoryManagerFactory;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class GeneratorServer {
  private final DefaultPlexusContainer plexusContainer;
  private final MavenSession session;

  private final DataInputStream in;
  private final MavenPluginManager mavenPluginManager;
  
  private final File generatorOutputDirectory;
  private final Logger logger;
  
  private final Maven maven;

  public static void main(String[] args) throws Exception {
    final long start = System.currentTimeMillis();
    new GeneratorServer(args);
    final long duration = System.currentTimeMillis() - start;
    System.out.print("\n[fcg] generating took " + duration + " ms: " + duration / 60000 + " min " + (duration % 60000) / 1000 + "sec");
  }

  public Logger getLogger() {
    return logger;
  }

  public GeneratorServer(String[] args)
    throws ComponentLookupException, IOException, MavenExecutionRequestPopulationException, SettingsBuildingException,
           PlexusContainerException, InterruptedException, InvalidRepositoryException {
    generatorOutputDirectory = new File(args[4]);
    //noinspection ResultOfMethodCallIgnored
    generatorOutputDirectory.mkdirs();

    in = new DataInputStream(new BufferedInputStream(System.in));

    plexusContainer = createPlexusContainer();

    logger = plexusContainer.getLoggerManager().getLoggerForComponent(null);
    mavenPluginManager = plexusContainer.lookup(MavenPluginManager.class);

    session = createSession(createExecutionRequest(args));
    maven = new Maven(plexusContainer, session);
    
    final List<String> generators = new ArrayList<String>(2);
    final URL generatorJarPath = new File(in.readUTF()).toURI().toURL();
    generators.add(in.readUTF());

    final int projectsCount = in.readUnsignedShort();
    final ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    try {
      for (int i = 0; i < projectsCount; i++) {
        final String pathname = in.readUTF();
        final String projectId = Integer.toString(i);
        executorService.submit(new Runnable() {
          @Override
          public void run() {
            try {
              MavenProject project = maven.readProject(new File(pathname), logger);
              if (project == null) {
                return;
              }

              String configFilePath = generate(project, generators, generatorJarPath);
              synchronized (System.out) {
                System.out.append("\n[fcg] generated: ").append(projectId).append(':').append(configFilePath);
                for (String sourceRoot : project.getCompileSourceRoots()) {
                  System.out.append('|').append(sourceRoot);
                }
                System.out.append("[/fcg]").flush();
              }
            }
            catch (Throwable e) {
              getLogger().error("Cannot generate flex config for " + pathname, e);
            }
          }
        });
      }
    }
    finally {
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.MINUTES);
    }
  }

  private void populateBuildNumberPluginFields(MavenProject project, Plugin plugin) throws Exception {
    MojoExecution mojoExecution = maven.createMojoExecution(plugin, "create", project);
    plexusContainer.lookup(BuildPluginManager.class).executeMojo(session, mojoExecution);
  }

  private String generate(final MavenProject project, final List<String> generators, final URL generatorJarPath) throws Exception {
    session.setCurrentProject(project);

    MojoExecution flexmojosMojoExecution = null;
    try {
      boolean flexmojosGeneratorFound = false;
      boolean buildHelperFound = false;
      boolean buildNumberFound = false;
      for (Plugin plugin : project.getBuildPlugins()) {
        final String pluginGroupId = plugin.getGroupId();
        if (pluginGroupId.equals("org.sonatype.flexmojos") || pluginGroupId.equals("net.flexmojos.oss")) {
          if (flexmojosMojoExecution == null && plugin.getArtifactId().equals("flexmojos-maven-plugin")) {
            flexmojosMojoExecution = maven.createMojoExecution(plugin, getCompileGoalName(project), project);
            for (Dependency dependency : plugin.getDependencies()) {
              if (dependency.getArtifactId().equals("flexmojos-threadlocaltoolkit-wrapper")) {
                AdditionalSourceRootUtil.addResourcesAsCompileSourceRoots(project);
                break;
              }
            }
          }
          else if (!flexmojosGeneratorFound && plugin.getArtifactId().equals("flexmojos-generator-mojo")) {
            AdditionalSourceRootUtil.addByGeneratorMojo(maven.createMojoExecution(plugin, "generate", project), session, project, getLogger());
            flexmojosGeneratorFound = true;
          }
        }
        else if (!buildHelperFound && plugin.getArtifactId().equals("build-helper-maven-plugin") && pluginGroupId.equals("org.codehaus.mojo")) {
          AdditionalSourceRootUtil.addByBuildHelper(maven.createMojoExecution(plugin, "add-source", project), session, project, getLogger());
          buildHelperFound = true;
        }
        else if (!buildNumberFound && plugin.getArtifactId().equals("buildnumber-maven-plugin") && pluginGroupId.equals("org.codehaus.mojo")) {
          populateBuildNumberPluginFields(project, plugin);
          buildNumberFound = true;
        }

        if (flexmojosMojoExecution != null && flexmojosGeneratorFound && buildHelperFound && buildNumberFound) {
          break;
        }
      }

      AdditionalSourceRootUtil.addByUnknownGeneratorMojo(project);

      assert flexmojosMojoExecution != null;
      final ClassRealm flexmojosPluginRealm = maven.getPluginRealm(flexmojosMojoExecution);
      flexmojosPluginRealm.addURL(generatorJarPath);

      final Mojo mojo = mavenPluginManager.getConfiguredMojo(Mojo.class, session, flexmojosMojoExecution);
      try {
        Class<?> configuratorClass = flexmojosPluginRealm.loadClass(generators.get(0));
        FlexConfigGenerator configurator = (FlexConfigGenerator)configuratorClass.getConstructor(MavenSession.class, File.class).newInstance(session, generatorOutputDirectory);
        configurator.preGenerate(project, Flexmojos.getClassifier(mojo));
        if ("swc".equals(project.getPackaging())) {
          configurator.generate(mojo);
        }
        else {
          configurator.generate(mojo, Flexmojos.getSourceFileForSwf(mojo));
        }
        return configurator.postGenerate(project);
      }
      finally {
        plexusContainer.release(mojo);
      }
    }
    finally {
      session.setCurrentProject(null);
      if (flexmojosMojoExecution != null) {
        maven.releaseMojoExecution(flexmojosMojoExecution);
      }
    }
  }

  private static String getCompileGoalName(final MavenProject project) {
    return "swc".equals(project.getPackaging()) ? "compile-swc" : "compile-swf";
  }

  public void resolveOutputs(WorkspaceReaderImpl.ArtifactData data) throws Exception {
    final MavenProject project = maven.readProject(data.file, logger);
    if (project == null) {
      getLogger().warn("Cannot read project while resolve output file for " + data.toString());
      return;
    }

    final MavenProject oldProject = session.getCurrentProject();
    MojoExecution flexmojosMojoExecution = null;
    try {
      session.setCurrentProject(project);
      for (Plugin plugin : project.getBuildPlugins()) {
        final String pluginGroupId = plugin.getGroupId();
        if ((pluginGroupId.equals("org.sonatype.flexmojos") || pluginGroupId.equals("net.flexmojos.oss"))
            && plugin.getArtifactId().equals("flexmojos-maven-plugin")) {
          flexmojosMojoExecution = maven.createMojoExecution(plugin, getCompileGoalName(project), project);
          break;
        }
      }

      if (flexmojosMojoExecution == null) {
        return;
      }

      // getPluginRealm creates plugin realm and populates pluginDescriptor.classRealm field
      maven.getPluginRealm(flexmojosMojoExecution);

      Mojo mojo = mavenPluginManager.getConfiguredMojo(Mojo.class, session, flexmojosMojoExecution);
      try {
        data.outputFile = new File(Flexmojos.getOutput(mojo));
        String[] localesRuntime = (String[])Flexmojos.invokePublicMethod(mojo, "getLocalesRuntime");
        data.linkReport = Flexmojos.getLinkReport(mojo);

        if (localesRuntime != null && localesRuntime.length > 0) {
          final Class<?> superclass = mojo.getClass().getSuperclass();
          Mojo localeMojo = (Mojo)Flexmojos.invokePublicMethod(mojo, "clone");

          final Method m = superclass.getDeclaredMethod("configureResourceBundle", String.class, superclass);
          m.setAccessible(true);
          String firstLocale = localesRuntime[0];
          m.invoke(mojo, firstLocale, localeMojo);

          //noinspection unchecked
          ((Map<String, String>)Flexmojos.invokePublicMethod(localeMojo, "getCache")).put("getProjectType", "rb.swc");
          data.localeOutputFilepathPattern = Flexmojos.getOutput(localeMojo).replace(firstLocale, "{_locale_}");
          // we don't release localeMojo (plexusContainer.release) - flexmojos doesn't do it too
        }
      }
      finally {
        plexusContainer.release(mojo);
      }
    }
    finally {
      session.setCurrentProject(oldProject);
      if (flexmojosMojoExecution != null) {
        maven.releaseMojoExecution(flexmojosMojoExecution);
      }
    }
  }

  private MavenSession createSession(MavenExecutionRequest request) throws ComponentLookupException {
    final ThreadSafeMavenSession session = new ThreadSafeMavenSession(plexusContainer, createRepositorySession(request), request,
                                                                      new DefaultMavenExecutionResult());
    // flexmojos uses old LegacyRepositorySystem
    plexusContainer.lookup(LegacySupport.class).setSession(session);
    return session;
  }

  private RepositorySystemSession createRepositorySession(MavenExecutionRequest request) throws ComponentLookupException {
    DefaultRepositorySystemSession session = (DefaultRepositorySystemSession)((DefaultMaven)plexusContainer.lookup(org.apache.maven.Maven.class)).newRepositorySession(request);
    if (!request.isUpdateSnapshots()) {
      session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER);
    }
    return session;
  }

  private MavenExecutionRequest createExecutionRequest(String[] args)
    throws ComponentLookupException, SettingsBuildingException, MavenExecutionRequestPopulationException, IOException,
           InvalidRepositoryException {
    MavenExecutionRequest request = new DefaultMavenExecutionRequest();
    request.setGlobalSettingsFile(new File(args[0]));
    if (!args[1].equals(" ")) {
      request.setUserSettingsFile(new File(args[1]));
    }
    request.setLocalRepository(plexusContainer.lookup(RepositorySystem.class).createLocalRepository(new File(args[2])));

    Properties systemProperties = new Properties();
    EnvironmentUtils.addEnvVars(systemProperties);
    //noinspection UseOfPropertiesAsHashtable
    systemProperties.putAll(System.getProperties());
    request.setSystemProperties(systemProperties);

    request.setOffline(args[3].equals("t")).setUpdateSnapshots(false).setCacheNotFound(true).setCacheTransferError(true);

    plexusContainer.lookup(MavenExecutionRequestPopulator.class).populateFromSettings(request, createSettings(request));
    
    // IDEA-76662
    final List<String> activeProfiles = request.getActiveProfiles();
    int profilesLength = in.readShort();
    if (profilesLength > 0) {
      while (profilesLength-- > 0) {
        activeProfiles.add(in.readUTF());
      }
    }

    request.setWorkspaceReader(new WorkspaceReaderImpl(in, this));
    return request;
  }

  private Settings createSettings(MavenExecutionRequest mavenExecutionRequest) throws ComponentLookupException, SettingsBuildingException {
    SettingsBuildingRequest request = new DefaultSettingsBuildingRequest();
    request.setSystemProperties(request.getSystemProperties());
    request.setGlobalSettingsFile(mavenExecutionRequest.getGlobalSettingsFile());
    request.setUserSettingsFile(mavenExecutionRequest.getUserSettingsFile());
    // IDEA-87004, getEffectiveSettings contains local repo as null, but our mavenExecutionRequest already has not-null local repo
    Settings settings = plexusContainer.lookup(SettingsBuilder.class).build(request).getEffectiveSettings();
    settings.setLocalRepository(mavenExecutionRequest.getLocalRepositoryPath().getPath());
    return settings;
  }

  private static DefaultPlexusContainer createPlexusContainer() throws PlexusContainerException, ComponentLookupException {
    ContainerConfiguration containerConfiguration = new DefaultContainerConfiguration()
            .setClassPathScanning(PlexusConstants.SCANNING_INDEX).setAutoWiring(true)
            .setClassWorld(new ClassWorld("plexus.core", Thread.currentThread().getContextClassLoader())).setName("maven");
    final DefaultPlexusContainer container = new DefaultPlexusContainer(containerConfiguration);

    final List<LocalRepositoryManagerFactory> factoryList = Collections.singletonList(container.lookup(LocalRepositoryManagerFactory.class, "simple"));
    final String mavenVersion = container.lookup(RuntimeInformation.class).getMavenVersion();
    // tracked impl is not suitable for us (our list of remote repo may be not equals - we don't want think about it)
    if (mavenVersion.length() >= 5 && mavenVersion.charAt(2) == '0' && mavenVersion.charAt(4) < '4') {
      final DefaultRepositorySystem repositorySystem = (DefaultRepositorySystem)container.lookup(RepositorySystem.class);
      try {
        repositorySystem.getClass().getMethod("setLocalRepositoryManagerFactories", List.class).invoke(repositorySystem, factoryList);
      }
      catch (Exception e) {
        container.getLoggerManager().getLoggerForComponent(null).warn("", e);
      }
    }
    else {
      ((DefaultLocalRepositoryProvider)container.lookup(LocalRepositoryProvider.class)).setLocalRepositoryManagerFactories(factoryList);
    }
    return container;
  }
}
