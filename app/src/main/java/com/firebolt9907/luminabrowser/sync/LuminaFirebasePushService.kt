package com.firebolt9907.luminabrowser.sync

import mozilla.components.lib.push.firebase.AbstractFirebasePushService

/**
 * The Firebase Cloud Messaging entry point for FxA push (instant Send-Tab / remote-tab-change
 * delivery). The Android system instantiates this class for incoming FCM messages; the base class
 * forwards each message to the globally-installed [mozilla.components.concept.push.PushProcessor]
 * (our [mozilla.components.feature.push.AutoPushFeature], installed in
 * [FirefoxSyncController.initPush]).
 *
 * This is a no-op unless a real `google-services.json` is present at build time — without it the
 * Google Services plugin isn't applied, so `gcm_defaultSenderId` never gets generated,
 * [FirefoxSyncController] never installs the processor, and FCM never routes here. See AGENTS.md.
 */
class LuminaFirebasePushService : AbstractFirebasePushService()
