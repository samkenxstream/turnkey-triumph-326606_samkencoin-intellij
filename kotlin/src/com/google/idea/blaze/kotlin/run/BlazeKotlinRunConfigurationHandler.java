/*
 * Copyright 2022 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.kotlin.run;

import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.run.BlazeCommandRunConfiguration;
import com.google.idea.blaze.base.run.ExecutorType;
import com.google.idea.blaze.base.run.confighandler.BlazeCommandRunConfigurationRunner;
import com.google.idea.blaze.java.run.BlazeJavaRunConfigurationHandler;
import com.google.idea.blaze.java.run.BlazeJavaRunProfileState;
import com.google.idea.blaze.kotlin.run.debug.KotlinProjectTraversingService;
import com.google.idea.blaze.kotlin.run.debug.KotlinxCoroutinesDebuggingLibProvider;
import com.google.idea.common.experiments.BoolExperiment;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.Key;
import java.util.Optional;
import org.jetbrains.kotlin.idea.debugger.coroutine.DebuggerConnection;

/**
 * Kotlin-specific handler for {@link BlazeCommandRunConfiguration}s.
 *
 * <p>This class is mainly needed to view coroutines debugging panel and enable coroutines plugin
 * for Kotlin targets that use coroutines and depend on the required versions of kotlinx-coroutines
 * library.
 */
public class BlazeKotlinRunConfigurationHandler extends BlazeJavaRunConfigurationHandler {

  private static final Key<RunProfileState> PROFILE_STATE_KEY =
      Key.create("blaze.run.profile.state");

  public BlazeKotlinRunConfigurationHandler(BlazeCommandRunConfiguration configuration) {
    super(configuration);
  }

  @Override
  public BlazeCommandRunConfigurationRunner createRunner(
      Executor executor, ExecutionEnvironment environment) {
    if (!ExecutorType.fromExecutor(executor).isFastBuildType()) {
      return new BlazeKotlinRunConfigurationRunner();
    }
    return super.createRunner(executor, environment);
  }

  private static class BlazeKotlinRunConfigurationRunner extends BlazeJavaRunConfigurationRunner {

    // Experiment supporting Kotlin coroutines debugging
    private static final BoolExperiment coroutinesDebuggingEnabled =
        new BoolExperiment("kotlin.coroutinesDebugging.enabled", false);

    @Override
    public RunProfileState getRunProfileState(Executor executor, ExecutionEnvironment env) {
      RunProfileState javaRunProfileState = super.getRunProfileState(executor, env);
      env.putCopyableUserData(PROFILE_STATE_KEY, javaRunProfileState);
      return javaRunProfileState;
    }

    @Override
    public boolean executeBeforeRunTask(ExecutionEnvironment env) {
      RunProfileState state = env.getCopyableUserData(PROFILE_STATE_KEY);

      if (coroutinesDebuggingEnabled.getValue() && state instanceof BlazeJavaRunProfileState) {
        // If the kotlinx-coroutines library is a transitive dependency of the target to debug, save
        // the path of kotlinx-coroutines-debugging library to be used as a javaagent during bazel
        // run and create a DebuggerConnection object to show the coroutines' panel during
        // debugging.
        BlazeCommandRunConfiguration config =
            BlazeCommandRunConfigurationRunner.getConfiguration(env);
        Optional<ArtifactLocation> libArtifact =
            KotlinProjectTraversingService.getInstance().findKotlinxCoroutinesLib(config);

        libArtifact
            .flatMap(artifact -> getCoroutinesDebuggingLib(artifact, config))
            .ifPresent(path -> attachCoroutinesPanel((BlazeJavaRunProfileState) state, path, env));
      }
      return super.executeBeforeRunTask(env);
    }

    private static void attachCoroutinesPanel(
        BlazeJavaRunProfileState state, String libAbsolutePath, ExecutionEnvironment env) {
      state.addKotlinxCoroutinesJavaAgent(libAbsolutePath);

      //noinspection unused go/checkreturnvalue
      DebuggerConnection unused =
          new DebuggerConnection(
              env.getProject(),
              /*configuration=*/ null,
              new JavaParameters(),
              /*modifyArgs=*/ false,
              /*alwaysShowPanel=*/ true);
    }

    private static Optional<String> getCoroutinesDebuggingLib(
        ArtifactLocation artifact, BlazeCommandRunConfiguration config) {
      return KotlinxCoroutinesDebuggingLibProvider.EP_NAME.getExtensionList().stream()
          .filter(p -> p.isApplicable(config.getProject()))
          .findFirst()
          .flatMap(p -> p.getKotlinxCoroutinesDebuggingLib(artifact, config));
    }
  }
}
