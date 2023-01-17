package com.imiyar.removebutterknife.parser

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import java.util.*

class ActionDelegate(private val project: Project, private val vFile: VirtualFile, private val psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) {

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }

        // 外部类处理
        checkClassType(psiClass)

        // 内部类处理
        psiClass.innerClasses.forEach {
            checkClassType(it)
        }

        return true
    }

    private fun checkClassType(psiClass: PsiClass) {
        val superType = psiClass.superClassType.toString()
        if (superType.contains("Activity")) {
            ActivityCodeParser(project, vFile, psiJavaFile, psiClass).execute()
        } else if (superType.contains("Fragment")) {
            FragmentCodeParser(project, vFile, psiJavaFile, psiClass).execute()
        } else if (superType.contains("ViewHolder") || superType.contains("Adapter<ViewHolder>")) {
            AdapterCodeParser(project, psiJavaFile, psiClass).execute()
        } else if (superType.contains("Adapter")) {

        } else if (superType.contains("Dialog")) {
            DialogCodeParser(project, psiJavaFile, psiClass).execute()
        } else { // 自定义View
            CustomViewCodeParser(project, vFile, psiJavaFile, psiClass).execute()
        }
    }

    /**
     * 检查是否有import butterknife相关，若没有引入butterknife，则不需要操作
     */
    private fun checkIsNeedModify(): Boolean {
        val importStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.lowercase(Locale.getDefault())?.contains("butterknife") == true
        }
        return importStatement != null
    }

}