package com.imiyar.removebutterknife

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import java.util.*

class ButterActionDelegate(project: Project, private val psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) {

    private val elementFactory = JavaPsiFacade.getInstance(project).elementFactory

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }

        return true
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