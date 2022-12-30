package com.imiyar.removebutterknife

import com.imiyar.removebutterknife.parser.ActionDelegate
import com.imiyar.removebutterknife.utils.Notifier
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ArrayUtil

class Entrance(private val event: AnActionEvent) {

    private val project = event.project
    private var javaFileCount = 0
    private var currFileIndex = 0
    private var parsedFileCount = 0 // 已完成的文件数量
    private var exceptionFileCount = 0 // 异常的文件数量

    fun run() {
        // 获取文件的多级目录
        val vFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        calJavaFileCount(vFiles)
        if (javaFileCount > 1) {
            showDialogWhenBatch { isContinue ->
                if (isContinue) {
                    startHandle(vFiles)
                }
            }
        } else {
            startHandle(vFiles)
        }
    }

    /**
     * 开始处理文件
     */
    private fun startHandle(vFiles: Array<out VirtualFile>?) {
        if (!ArrayUtil.isEmpty(vFiles)) {
            ProgressManager.getInstance().runProcessWithProgressSynchronously({
                val progressIndicator = ProgressManager.getInstance().progressIndicator
                vFiles?.forEachIndexed { index, vFile ->
                    progressIndicator.checkCanceled()
                    progressIndicator.text2 = "($index/${vFiles.size}) '${vFile.name}'... "
                    progressIndicator.fraction = index.toDouble() / vFiles.size
                    handle(vFile)
                }
                showResult()
            }, "正在处理Java文件", true, project)
        }
    }

    private fun handle(vFile: VirtualFile) {
        if (vFile.isDirectory) {
            handleDirectory(vFile)
        } else {
            if (vFile.fileType is JavaFileType) {
                val psiFile = PsiManager.getInstance(project!!).findFile(vFile)
                val psiClass = PsiTreeUtil.findChildOfAnyType(psiFile, PsiClass::class.java)
                handleSingleVirtualFile(vFile, psiFile, psiClass)
            }
        }
    }

    private fun handleSingleVirtualFile(vFile: VirtualFile, psiFile: PsiFile?, psiClass: PsiClass?) {
        if (psiFile is PsiJavaFile && psiClass != null) {
            currFileIndex++
            try {
                writeAction(psiFile) {
                    val parsed = ActionDelegate(project!!, vFile, psiFile, psiClass).parse()
                    if (parsed) {
                        parsedFileCount++
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                exceptionFileCount++
                Notifier.notifyError(project!!, "$currFileIndex. ${vFile.name} 出现异常，处理结束 × ")
            }
        }
    }

    private fun handleDirectory(dir: VirtualFile) {
        dir.children.forEach {
            handle(it)
        }
    }

    private fun showResult() {
        Notifier.notifyInfo(
            project!!,
            "RemoveButterKnife处理结果：${currFileIndex}个Java文件, ${parsedFileCount}个完成, ${exceptionFileCount}个异常"
        )
    }

    /**
     * 计算该目录下一共有多少个文件
     */
    private fun calJavaFileCount(vFiles: Array<VirtualFile>?) {
        javaFileCount = 0
        if (!ArrayUtil.isEmpty(vFiles)) {
            vFiles?.forEach { vFile ->
                count(vFile)
            }
        }
    }

    private fun count(vFile: VirtualFile) {
        if (vFile.isDirectory) {
            vFile.children.forEach {
                count(it)
            }
        } else {
            if (vFile.fileType is JavaFileType) {
                // PSI文件：http://www.jetbrains.org/intellij/sdk/docs/basics/architectural_overview/psi_files.html
                val psiFile = PsiManager.getInstance(project!!).findFile(vFile)
                val psiClass = PsiTreeUtil.findChildOfAnyType(psiFile, PsiClass::class.java)
                if (psiFile is PsiJavaFile && psiClass != null) {
                    javaFileCount++
                }
            }
        }
    }

    /**
     * 弹框提示进行批量操作文件
     */
    private fun showDialogWhenBatch(nextAction: (isContinue: Boolean) -> Unit) {
        val dialogBuilder = DialogBuilder()
        dialogBuilder.setErrorText("你正在批量处理Java文件，总数$javaFileCount, 可能需要较长时间，是否继续？")
        dialogBuilder.setOkOperation {
            nextAction.invoke(true)
            dialogBuilder.dialogWrapper.close(0)
        }
        dialogBuilder.setCancelOperation {
            nextAction.invoke(false)
            dialogBuilder.dialogWrapper.close(0)
        }
        dialogBuilder.showModal(true)
    }

    private fun writeAction(psiJavaFile: PsiFile, commandName: String = "RemoveButterKnifeWriteAction", runnable: Runnable) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterKnifeGroupID", runnable, psiJavaFile)
    }

}