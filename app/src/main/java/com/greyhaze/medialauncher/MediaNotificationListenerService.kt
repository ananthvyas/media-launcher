package com.greyhaze.medialauncher

import android.service.notification.NotificationListenerService

/**
 * NotificationListenerService required by Android to allow the launcher
 * to access active media sessions across apps using MediaSessionManager.
 */
class MediaNotificationListenerService : NotificationListenerService()

