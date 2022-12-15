package com.imiyar.removebutterknife;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;

class RemoveAction: AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        Entrance(event).run()
    }

}
