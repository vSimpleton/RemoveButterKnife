package com.imiyar.removebutterknife.parser

import com.imiyar.removebutterknife.utils.getLayoutRes
import com.imiyar.removebutterknife.utils.isOnlyContainsTarget
import com.imiyar.removebutterknife.utils.underLineToHump
import com.imiyar.removebutterknife.utils.withViewBinding
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod

class CustomViewCodeParser(
    private val project: Project,
    private val vFile: VirtualFile,
    private val psiJavaFile: PsiJavaFile,
    private val psiClass: PsiClass
) : BaseCodeParser(project, psiJavaFile, psiClass) {

    init {
        findBindViewAnnotation()
        findOnClickAnnotation()
    }

    override fun findViewInsertAnchor() {
        val method = findLayoutMethod()
        method?.body?.statements?.forEach { statement ->
            if (statement.text.contains("R.layout") || statement.text.contains("LayoutInflater.from(context).inflate")) {
                val layoutRes = statement.text.trim().getLayoutRes()
                val bindingName = layoutRes.underLineToHump().withViewBinding()
                addBindingField("private $bindingName mBinding;\n")
                addImportStatement(vFile, layoutRes)

                val array: Array<String>
                // 如果statement中含有=，则表示statement中已经有变量了（全局变量或局部变量）
                // TODO 使用的方式比较傻，后面看如何优化
                if (statement.text.trim().contains("=")) {
                    array = statement.text.trim().split("=").toTypedArray()
                    if (array[0].isOnlyContainsTarget("View")) { // 已经有局部变量
                        addMethodStatement(method, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(view);", psiClass))
                        changeBindingStatement(method, statement, elementFactory.createStatementFromText("View view = ${statement.text.trim().split("=")[1]}", psiClass))
                    } else { // 没有局部变量，那就代表有全局变量
                        addMethodStatement(method, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(${array[0]});", psiClass))
                    }
                } else { // 没有变量，则手动添加一个局部变量
                    addMethodStatement(method, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(view);", psiClass))
                    changeBindingStatement(method, statement, elementFactory.createStatementFromText("View view = ${statement.text}", psiClass))
                }
            }
        }

        psiClass.methods.forEach {
            it.body?.statements?.forEach { statement ->
                changeBindViewStatement(statement)
            }
        }
    }

    private fun findLayoutMethod(): PsiMethod? {
        var resultMethod: PsiMethod? = null
        psiClass.methods.forEach jump@{ method ->
            method.body?.statements?.forEach { statement ->
                if (statement.text.contains("R.layout") || statement.text.contains("LayoutInflater.from(context).inflate")) {
                    resultMethod = method
                    return@jump
                }
            }
        }
        return resultMethod
    }

    override fun findClickInsertAnchor() {
        val method = findLayoutMethod()
        method?.let {
            insertOnClickMethod(it)
        }
    }

}