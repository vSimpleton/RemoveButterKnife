package com.imiyar.removebutterknife.utils

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object Notifier {

    private val notificationGroup = NotificationGroup("RemoveButterKnife Notification Group", NotificationDisplayType.BALLOON, true)

    fun notifyError(project: Project, content: String) {
        notificationGroup.createNotification(content, NotificationType.ERROR).notify(project)
    }

    fun notifyInfo(project: Project, content: String) {
        notificationGroup.createNotification(content, NotificationType.INFORMATION).notify(project)
    }

    fun notifyWarning(project: Project, content: String) {
        notificationGroup.createNotification(content, NotificationType.WARNING).notify(project)
    }

}