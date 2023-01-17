package com.imiyar.removebutterknife.parser

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement

class DialogCodeParser(project: Project, psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) : BaseCodeParser(project, psiJavaFile, psiClass) {

    private var resultMethod: PsiMethod? = null
    private var resultStatement: PsiStatement? = null

    init {
        findBindViewAnnotation(false)
        findOnClickAnnotation()
    }

    override fun findViewInsertAnchor() {
        findMethodByButterKnifeBind()
        resultMethod?.let { method ->
            resultStatement?.let { statement ->
                insertInitViewMethod(method, statement)
            }
        }
    }

    /**
     * 找到ButterKnife.bind的绑定语句所在的方法
     */
    private fun findMethodByButterKnifeBind() {
        run jump@{
            psiClass.methods.forEach { method ->
                method.body?.statements?.forEach { statement ->
                    if (statement.text.trim().contains("ButterKnife.bind(")) {
                        if (method.isConstructor) {
                            resultMethod = method
                            resultStatement = statement
                            return@jump
                        }
                    }
                }
            }
        }
    }

    override fun findClickInsertAnchor() {
        resultMethod?.let {
            insertOnClickMethod(it, false, "null")
        }
    }

}