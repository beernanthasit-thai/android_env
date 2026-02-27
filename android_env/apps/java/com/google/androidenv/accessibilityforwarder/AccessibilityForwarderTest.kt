// Copyright 2026 DeepMind Technologies Limited.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.androidenv.accessibilityforwarder

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.common.truth.Truth.assertThat
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.testing.GrpcCleanupRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestParameterInjector
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestParameterInjector::class)
class AccessibilityForwarderTest {

  @get:Rule(order = 1) val cleanupRule = GrpcCleanupRule()

  class FakeAccessibilityService : A11yServiceGrpcKt.A11yServiceCoroutineImplBase() {
    var sendForestChecker: (AndroidAccessibilityForest) -> String = { _ -> "" }
    var sendEventChecker: (A11yEvent) -> String = { _ -> "" }

    override fun bidi(requests: Flow<ClientToServer>): Flow<ServerToClient> {
        // We can't easily inspect the flow here in a synchronous test way,
        // so we'll collect it in a background job or similar if we were running a full integration test.
        // For unit testing here, we might need a different approach if we want to intercept requests.
        // However, given the Robolectric environment, we can launch a coroutine to collect.

        // For simplicity in this mock, let's just return an empty flow or similar,
        // but we need to capture the requests to verify them.

        // Since `bidi` is called by the client, we can intercept the flow passed to it.
        // But `requests` is a Flow, so we need to collect it.

        return kotlinx.coroutines.flow.flow {
            requests.collect { request ->
                if (request.hasForest()) {
                    sendForestChecker(request.forest)
                } else if (request.hasEvent()) {
                    sendEventChecker(request.event)
                }
                emit(serverToClient {})
            }
        }
    }
  }

  protected lateinit var forwarder: AccessibilityForwarder
  protected val fakeA11yService = FakeAccessibilityService()
  protected val channel by lazy {
    val serverName: String = InProcessServerBuilder.generateName()
    cleanupRule.register(
      InProcessServerBuilder.forName(serverName)
        .directExecutor()
        .addService(fakeA11yService)
        .build()
        .start()
    )
    cleanupRule.register(InProcessChannelBuilder.forName(serverName).directExecutor().build())
  }

  /** Initializes [forwarder] and [LogFlags] from the given args. */
  fun createForwarder(
    logAccessibilityTree: Boolean = false,
    a11yTreePeriodMs: Long = 0,
    grpcHost: String = "10.0.2.2",
    grpcPort: Int = 0,
    a11yWindows: MutableList<AccessibilityWindowInfo>? = null,
  ) {
    LogFlags.logAccessibilityTree = logAccessibilityTree
    LogFlags.a11yTreePeriodMs = a11yTreePeriodMs
    LogFlags.grpcHost = grpcHost
    LogFlags.grpcPort = grpcPort
    forwarder = AccessibilityForwarder({ _, _ -> channel })
    if (a11yWindows == null) {
      shadowOf(forwarder).setWindows(mutableListOf(AccessibilityWindowInfo.obtain()))
    } else {
      shadowOf(forwarder).setWindows(a11yWindows)
    }
  }

  @Test
  fun onInterrupt_doesNotCrash() {
    // Arrange.
    createForwarder(logAccessibilityTree = false)
    fakeA11yService.sendEventChecker = { _: A11yEvent ->
      assertFalse(true) // This should not be called.
      "" // This should be unreachable
    }

    // Act.
    forwarder.onInterrupt()

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_nullEventShouldBeIgnored() {
    // Arrange.
    createForwarder(logAccessibilityTree = false)
    fakeA11yService.sendEventChecker = { _: A11yEvent ->
      assertFalse(true) // This should not be called.
      "" // This should be unreachable
    }

    // Act.
    forwarder.onAccessibilityEvent(null)

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_knownEventWithNoInformationShouldNotBeEmitted() {
    // Arrange.
    createForwarder(logAccessibilityTree = false)
    var nodeInfo = AccessibilityNodeInfo()
    nodeInfo.setContentDescription("")
    var event = AccessibilityEvent()
    shadowOf(event).setSourceNode(nodeInfo)
    fakeA11yService.sendEventChecker = { _: A11yEvent ->
      assertFalse(true) // This should not be called.
      "" // This should be unreachable
    }

    // Act.
    forwarder.onAccessibilityEvent(event)

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_typeViewClicked_sendEventViaGrpc() {
    // Arrange.
    createForwarder(logAccessibilityTree = false, grpcPort = 1234)
    forwarder = AccessibilityForwarder({ _, _ -> channel })
    var nodeInfo = AccessibilityNodeInfo()
    nodeInfo.setContentDescription("My Content Description")
    nodeInfo.setText("My Source Text")
    nodeInfo.setClassName("AwesomeClass")
    var event = AccessibilityEvent()
    event.setEventTime(1357924680)
    event.setEventType(AccessibilityEvent.TYPE_VIEW_CLICKED)
    event.getText().add("Some text!")
    event.setPackageName("some.loooong.package.name")
    shadowOf(event).setSourceNode(nodeInfo)
    fakeA11yService.sendEventChecker = { request: A11yEvent ->
      // Check that all fields are consistent with how they were set above.
      assertThat(request.eventMap.get("event_type")).isEqualTo("TYPE_VIEW_CLICKED")
      assertThat(request.eventMap.get("event_package_name")).isEqualTo("some.loooong.package.name")
      assertThat(request.eventMap.get("source_content_description"))
        .isEqualTo("My Content Description")
      assertThat(request.eventMap.get("source_text")).isEqualTo("My Source Text")
      assertThat(request.eventMap.get("source_class_name")).isEqualTo("AwesomeClass")
      assertThat(request.eventMap.get("event_text")).isEqualTo("Some text!")
      assertThat(request.eventMap.get("event_timestamp_ms")).isEqualTo("1357924680")
      // No error message
      ""
    }

    // Act.
    forwarder.onAccessibilityEvent(event)

    // Allow some time for the bidi channel to process
    Thread.sleep(200)

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_typeViewTextChanged_ensureAllFieldsForwarded() {
    // Arrange.
    createForwarder(logAccessibilityTree = false, grpcPort = 1234)
    var nodeInfo = AccessibilityNodeInfo()
    nodeInfo.setContentDescription("My Content Description")
    nodeInfo.setText("My Source Text")
    nodeInfo.setClassName("AwesomeClass")
    var event = AccessibilityEvent()
    event.setEventTime(1357924680)
    event.setEventType(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
    event.getText().add("Some text!")
    event.fromIndex = 7
    event.beforeText = "Old words"
    event.addedCount = 12
    event.removedCount = 9
    event.setPackageName("some.loooong.package.name")
    shadowOf(event).setSourceNode(nodeInfo)
    fakeA11yService.sendEventChecker = { request: A11yEvent ->
      // Check that all fields are consistent with how they were set above.
      assertThat(request.eventMap.get("event_type")).isEqualTo("TYPE_VIEW_TEXT_CHANGED")
      assertThat(request.eventMap.get("event_package_name")).isEqualTo("some.loooong.package.name")
      assertThat(request.eventMap.get("source_content_description"))
        .isEqualTo("My Content Description")
      assertThat(request.eventMap.get("source_text")).isEqualTo("My Source Text")
      assertThat(request.eventMap.get("source_class_name")).isEqualTo("AwesomeClass")
      assertThat(request.eventMap.get("event_text")).isEqualTo("Some text!")
      assertThat(request.eventMap.get("event_timestamp_ms")).isEqualTo("1357924680")
      assertThat(request.eventMap.get("from_index")).isEqualTo("7")
      assertThat(request.eventMap.get("before_text")).isEqualTo("Old words")
      assertThat(request.eventMap.get("added_count")).isEqualTo("12")
      assertThat(request.eventMap.get("removed_count")).isEqualTo("9")
      assertFalse(request.eventMap.containsKey("to_index"))
      assertFalse(request.eventMap.containsKey("view_id"))
      assertFalse(request.eventMap.containsKey("action"))
      assertFalse(request.eventMap.containsKey("movement_granularity"))
      assertFalse(request.eventMap.containsKey("scroll_delta_x"))
      assertFalse(request.eventMap.containsKey("scroll_delta_y"))
      // No error message
      ""
    }

    // Act.
    forwarder.onAccessibilityEvent(event)
    Thread.sleep(200)

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_typeViewScrolled_ensureAllFieldsForwarded() {
    // Arrange.
    createForwarder(logAccessibilityTree = false, grpcPort = 1234)
    var nodeInfo = AccessibilityNodeInfo()
    nodeInfo.setContentDescription("My Content Description")
    nodeInfo.setText("My Source Text")
    nodeInfo.setClassName("AwesomeClass")
    var event = AccessibilityEvent()
    event.setEventTime(1357924680)
    event.setEventType(AccessibilityEvent.TYPE_VIEW_SCROLLED)
    event.getText().add("Some text!")
    event.scrollDeltaX = 13
    event.scrollDeltaY = 27
    event.setPackageName("some.loooong.package.name")
    shadowOf(event).setSourceNode(nodeInfo)
    fakeA11yService.sendEventChecker = { request: A11yEvent ->
      // Check that all fields are consistent with how they were set above.
      assertThat(request.eventMap.get("event_type")).isEqualTo("TYPE_VIEW_SCROLLED")
      assertThat(request.eventMap.get("event_package_name")).isEqualTo("some.loooong.package.name")
      assertThat(request.eventMap.get("source_content_description"))
        .isEqualTo("My Content Description")
      assertThat(request.eventMap.get("source_text")).isEqualTo("My Source Text")
      assertThat(request.eventMap.get("source_class_name")).isEqualTo("AwesomeClass")
      assertThat(request.eventMap.get("event_text")).isEqualTo("Some text!")
      assertThat(request.eventMap.get("event_timestamp_ms")).isEqualTo("1357924680")
      assertThat(request.eventMap.get("scroll_delta_x")).isEqualTo("13")
      assertThat(request.eventMap.get("scroll_delta_y")).isEqualTo("27")
      assertFalse(request.eventMap.containsKey("from_index"))
      assertFalse(request.eventMap.containsKey("to_index"))
      assertFalse(request.eventMap.containsKey("before_text"))
      assertFalse(request.eventMap.containsKey("added_count"))
      assertFalse(request.eventMap.containsKey("removed_count"))
      // No error message
      ""
    }

    // Act.
    forwarder.onAccessibilityEvent(event)
    Thread.sleep(200)

    // Assert.
    // See `sendEventChecker` above.
  }

  @Test
  fun onAccessibilityEvent_typeViewTextTraversedAtMovementGranularity_ensureAllFieldsForwarded() {
    // Arrange.
    createForwarder(logAccessibilityTree = false, grpcPort = 1234)
    var nodeInfo = AccessibilityNodeInfo()
    nodeInfo.setContentDescription("My Content Description")
    nodeInfo.setText("My Source Text")
    nodeInfo.setClassName("AwesomeClass")
    nodeInfo.viewIdResourceName = "this.big.old.view.id"
    var event = AccessibilityEvent()
    event.setEventTime(1357924680)
    event.setEventType(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY)
    event.getText().add("Some text!")
    event.setPackageName("some.loooong.package.name")
    event.movementGranularity = 5
    event.fromIndex = 6
    event.toIndex = 8
    event.action = 23
    shadowOf(event).setSourceNode(nodeInfo)
    fakeA11yService.sendEventChecker = { request: A11yEvent ->
      // Check that all fields are consistent with how they were set above.
      assertThat(request.eventMap.get("event_type"))
        .isEqualTo("TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY")
      assertThat(request.eventMap.get("event_package_name")).isEqualTo("some.loooong.package.name")
      assertThat(request.eventMap.get("source_content_description"))
        .isEqualTo("My Content Description")
      assertThat(request.eventMap.get("source_text")).isEqualTo("My Source Text")
      assertThat(request.eventMap.get("source_class_name")).isEqualTo("AwesomeClass")
      assertThat(request.eventMap.get("event_text")).isEqualTo("Some text!")
      assertThat(request.eventMap.get("event_timestamp_ms")).isEqualTo("1357924680")
      assertThat(request.eventMap.get("movement_granularity")).isEqualTo("5")
      assertThat(request.eventMap.get("from_index")).isEqualTo("6")
      assertThat(request.eventMap.get("to_index")).isEqualTo("8")
      assertThat(request.eventMap.get("view_id")).isEqualTo("this.big.old.view.id")
      assertThat(request.eventMap.get("action")).isEqualTo("23")
      // No error message
      ""
    }

    // Act.
    forwarder.onAccessibilityEvent(event)
    Thread.sleep(200)

    // Assert.
    // See `sendEventChecker` above.
  }


  @Test
  fun logAccessibilityTreeFalse_doesNotLogAccessibilityTree() {
    // Arrange.
    createForwarder(logAccessibilityTree = false, a11yTreePeriodMs = 10, grpcPort = 13579)
    fakeA11yService.sendForestChecker = { _: AndroidAccessibilityForest ->
      assertFalse(true) // This should not be called.
      "" // This should be unreachable
    }

    // Act.
    Thread.sleep(1000) // Sleep a bit to give time to trigger the tree logging function.

    // Assert.
    // See `sendForestChecker` above.
  }

  @Test
  fun grpcPortZero_doesNotSendTree() {
    // Arrange.
    createForwarder(logAccessibilityTree = true, a11yTreePeriodMs = 10, grpcPort = 0)
    fakeA11yService.sendForestChecker = { _: AndroidAccessibilityForest ->
      assertFalse(true) // This should not be called.
      "" // This should be unreachable
    }

    // Act.
    Thread.sleep(1000) // Sleep a bit to give time to trigger the tree logging function.

    // Assert.
    // See `sendForestChecker` above.
  }

  @Test
  fun grpcPortPositive_shouldSendTreeViaGrpc() {
    // Arrange.
    val window = AccessibilityWindowInfo()
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_SYSTEM)
    createForwarder(
      logAccessibilityTree = true,
      a11yTreePeriodMs = 10,
      grpcPort = 1234,
      a11yWindows = mutableListOf(window),
    )
    fakeA11yService.sendForestChecker = { request: AndroidAccessibilityForest ->
      // Check that we get only a single window.
      assertThat(request.windowsList.size).isEqualTo(1)
      // And that its type is what we set above.
      assertThat(request.windowsList[0].windowType)
        .isEqualTo(AndroidAccessibilityWindowInfo.WindowType.TYPE_SYSTEM)
      // The error message
      "Something went wrong!"
    }

    // Act.
    Thread.sleep(1000) // Sleep a bit to give time to trigger the tree logging function.

    // Assert.
    // See `sendForestChecker` above.
  }

  @Test
  fun grpcPortPositiveAndHost_shouldSendTreeViaGrpc() {
    // Arrange.
    fakeA11yService.sendForestChecker = { request: AndroidAccessibilityForest ->
      // Check that we get only a single window.
      assertThat(request.windowsList.size).isEqualTo(1)
      // And that its type is what we set above.
      assertThat(request.windowsList[0].windowType)
        .isEqualTo(AndroidAccessibilityWindowInfo.WindowType.TYPE_ACCESSIBILITY_OVERLAY)
      "" // Return no error.
    }
    val window = AccessibilityWindowInfo()
    shadowOf(window).setType(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
    createForwarder(
      logAccessibilityTree = true,
      a11yTreePeriodMs = 500,
      grpcHost = "amazing.host",
      grpcPort = 4321,
      a11yWindows = mutableListOf(window),
    )

    // Act.
    Thread.sleep(1000) // Sleep a bit to give time to trigger the tree logging function.

    // Assert.
    // See `sendForestChecker` above.
  }

}
