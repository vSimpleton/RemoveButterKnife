package com.imiyar.removebutterknife.parser

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement

class AdapterCodeParser(project: Project, psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) : BaseCodeParser(project, psiJavaFile, psiClass) {

    init {
        findBindViewAnnotation(false)
        findOnClickAnnotation()
    }

    private var resultMethod: PsiMethod? = null
    private var resultStatement: PsiStatement? = null

    override fun findViewInsertAnchor() {
        findMethodByButterKnifeBind()
        val parameterName = findMethodParameterName()
        resultMethod?.let {
            innerBindViewFieldLists.forEach { pair ->
                resultStatement?.let { statement ->
                    addMethodStatement(it, statement, elementFactory.createStatementFromText("${pair.second} = $parameterName.findViewById(R.id.${pair.first});", psiClass))
                }
            }
        }
    }

    /**
     * 找到ViewHolder构造函数的参数名称
     */
    private fun findMethodParameterName(): String {
        var parameterName = ""
        resultMethod?.let {
            it.parameterList.parameters.forEach { parameter ->
                if (parameter.type.toString() == "PsiType:View") {
                    parameterName = parameter.name
                    return@forEach
                }
            }
        }
        return parameterName
    }

    /**
     * 找到ButterKnife.bind的绑定语句所在的方法
     */
    private fun findMethodByButterKnifeBind() {
        psiClass.methods.forEach jump@{ method ->
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

    override fun findClickInsertAnchor() {
        val parameterName = findMethodParameterName()
        resultMethod?.let {
            insertOnClickMethodByFVB(it, parameterName)
        }
    }

}