/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.fnexecution.wire;

import static org.apache.beam.runners.core.construction.BeamUrns.getUrn;
import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.util.Map;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.runners.core.construction.ModelCoders;
import org.apache.beam.runners.core.construction.RehydratedComponents;
import org.apache.beam.runners.core.construction.SyntheticComponents;
import org.apache.beam.runners.core.construction.graph.PipelineNode.PCollectionNode;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.util.WindowedValue;

/** Helpers to construct coders for gRPC port reads and writes. */
public class WireCoders {
  /**
   * Creates an SDK-side wire coder for a port read/write for the given PCollection. Coders that are
   * unknown to the runner are wrapped with length-prefix coders. The inner element coders are kept
   * intact so that SDK harnesses can reconstruct the original elements.
   *
   * <p>Adds all necessary coders to the components builder.
   *
   * @return id of a windowed value coder containing the PCollection's element coder
   */
  public static String addSdkWireCoder(
      PCollectionNode pCollectionNode, RunnerApi.Components.Builder components) {
    return addWireCoder(pCollectionNode, components, false);
  }

  /**
   * Creates a runner-side wire coder for a port read/write for the given PCollection. Unknown
   * coders are replaced with length-prefixed byte arrays.
   *
   * <p>Adds all necessary coders to the components builder.
   *
   * @return id of a windowed value coder containing the PCollection's element coder
   */
  public static String addRunnerWireCoder(
      PCollectionNode pCollectionNode, RunnerApi.Components.Builder components) {
    return addWireCoder(pCollectionNode, components, true);
  }

  /**
   * Instantiates a runner-side wire coder for the given PCollection. Any component coders that are
   * unknown by the runner are replaced with length-prefixed byte arrays.
   *
   * @return a windowed value coder containing the PCollection's element coder
   */
  public static <T> Coder<WindowedValue<T>> instantiateRunnerWireCoder(
      PCollectionNode pCollectionNode, RunnerApi.Components components) throws IOException {
    // NOTE: We discard the new set of components so we don't bother to ensure it's consistent with
    // the caller's view.
    RunnerApi.Components.Builder builder = components.toBuilder();
    String protoCoderId = addRunnerWireCoder(pCollectionNode, builder);
    Coder<?> javaCoder = RehydratedComponents.forComponents(builder.build()).getCoder(protoCoderId);
    checkArgument(
        javaCoder instanceof WindowedValue.WindowedValueCoder,
        "Unexpected Deserialized %s type, expected %s, got %s",
        RunnerApi.Coder.class.getSimpleName(),
        WindowedValue.WindowedValueCoder.class.getSimpleName(),
        javaCoder.getClass());
    return (Coder<WindowedValue<T>>) javaCoder;
  }

  private static String addWireCoder(
      PCollectionNode pCollectionNode,
      RunnerApi.Components.Builder components,
      boolean useByteArrayCoder) {
    String elementCoderId = pCollectionNode.getPCollection().getCoderId();
    String windowingStrategyId = pCollectionNode.getPCollection().getWindowingStrategyId();
    String windowCoderId =
        components.getWindowingStrategiesOrThrow(windowingStrategyId).getWindowCoderId();

    Map<String, String> configurations = components.getConfigurationsMap();

    // check whether the configured wire coder is WindowedValueCoder(full or value only).
    String wireCoderName = getUrn(RunnerApi.ConfigKeys.Enum.WIRE_CODER);
    String windowedValueCoderName = getUrn(RunnerApi.StandardCoders.Enum.WINDOWED_VALUE);
    String valueOnlyWindowedValueCoderName =
        getUrn(RunnerApi.StandardCoders.Enum.VALUE_ONLY_WINDOWED_VALUE);
    String inputWireCoder = configurations.getOrDefault(wireCoderName, windowedValueCoderName);
    checkArgument(
        inputWireCoder.equals(windowedValueCoderName)
            || inputWireCoder.equals(valueOnlyWindowedValueCoderName),
        "Unexpected wire coder name %s, currently only %s or %s are supported!",
        inputWireCoder,
        windowedValueCoderName,
        valueOnlyWindowedValueCoderName);

    Boolean useValueOnlyWindowedValueCoder = inputWireCoder.equals(valueOnlyWindowedValueCoderName);
    RunnerApi.Coder windowedValueCoder =
        useValueOnlyWindowedValueCoder
            ? ModelCoders.valueOnlyWindowedValueCoder(elementCoderId, windowCoderId)
            : ModelCoders.windowedValueCoder(elementCoderId, windowCoderId);

    // Add the original WindowedValue<T, W> coder to the components;
    String windowedValueId =
        SyntheticComponents.uniqueId(
            String.format("fn/wire/%s", pCollectionNode.getId()), components::containsCoders);
    components.putCoders(windowedValueId, windowedValueCoder);
    return LengthPrefixUnknownCoders.addLengthPrefixedCoder(
        windowedValueId, components, useByteArrayCoder);
  }

  // Not instantiable.
  private WireCoders() {}
}
