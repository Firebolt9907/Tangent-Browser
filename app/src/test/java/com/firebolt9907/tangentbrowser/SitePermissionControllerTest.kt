package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.permissions.SitePermissionController
import mozilla.components.concept.engine.permission.Permission
import mozilla.components.concept.engine.permission.PermissionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermissionControllerTest {
    private class Request(override val id: String) : PermissionRequest {
        override val uri = "https://example.com"
        override val permissions = emptyList<Permission>()
        var granted = false
        var rejected = false
        override fun grant(permissions: List<Permission>) { granted = true }
        override fun reject() { rejected = true }
        override fun merge(other: PermissionRequest) = Unit
    }

    @Test fun sameOriginRequestOnlyAppearsInItsOwningTab() {
        val controller = SitePermissionController()
        val request = Request("custom-request")
        controller.onContentPermissionRequest(request, "custom-tab")
        assertSame(request, controller.contentRequestFor("custom-tab"))
        assertNull(controller.contentRequestFor("main-tab"))
        assertNull(controller.contentRequestFor(null))
        controller.grantCurrent(request.id)
        assertTrue(request.granted)
        assertNull(controller.contentRequestFor("custom-tab"))
    }

    @Test fun concurrentRequestCannotReplaceTheOwner() {
        val controller = SitePermissionController()
        val first = Request("first")
        val second = Request("second")
        controller.onContentPermissionRequest(first, "main-tab")
        controller.onContentPermissionRequest(second, "custom-tab")
        assertTrue(second.rejected)
        assertFalse(first.rejected)
        assertSame(first, controller.contentRequestFor("main-tab"))
        assertNull(controller.contentRequestFor("custom-tab"))
    }

    @Test fun exitingCardCannotResolveANewerRequest() {
        val controller = SitePermissionController()
        val old = Request("old")
        val current = Request("current")
        controller.onContentPermissionRequest(old, "main-tab")
        controller.onCancelContentPermissionRequest(old)
        controller.onContentPermissionRequest(current, "custom-tab")
        controller.grantCurrent(old.id)
        controller.rejectCurrent(old.id)
        controller.onCancelContentPermissionRequest(old)
        assertFalse(current.granted)
        assertFalse(current.rejected)
        assertSame(current, controller.contentRequestFor("custom-tab"))
        controller.rejectCurrent(current.id)
        assertTrue(current.rejected)
        assertNull(controller.contentRequest.value)
    }
}
