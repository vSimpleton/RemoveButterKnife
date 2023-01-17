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

class CustomViewCodeParser(project: Project, private val vFile: VirtualFile, psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) :
    BaseCodeParser(project, psiJavaFile, psiClass) {

    init {
        findBindViewAnnotation()
        findOnClickAnnotation()
    }

    override fun findViewInsertAnchor() {
        val method = findLayoutMethod()
        var layoutRes = ""
        var bindingName = ""
        method?.body?.statements?.forEach { statement ->
            if (statement.text.contains("R.layout") || statement.text.contains("LayoutInflater.from(context).inflate") || statement.text.contains("inflate(context")) {
                layoutRes = statement.text.trim().getLayoutRes()
                bindingName = layoutRes.underLineToHump().withViewBinding()
                addBindingField("private $bindingName mBinding;\n")
                addImportStatement(vFile, layoutRes)
            }
        }

        val butterKnifeBindMethod = findButterKnifeBindMethod()
        run jump@{
            butterKnifeBindMethod?.body?.statements?.forEach { statement ->
                if (statement.text.contains("R.layout") || statement.text.contains("LayoutInflater.from(context).inflate") || statement.text.contains("inflate(context")) {
                    val array: Array<String>
                    // 如果statement中含有=，则表示statement中已经有变量了（全局变量或局部变量）
                    if (statement.text.trim().contains("=")) {
                        array = statement.text.trim().split("=").toTypedArray()
                        if (array[0].isOnlyContainsTarget("View")) { // 已经有局部变量
                            addMethodStatement(butterKnifeBindMethod, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(view);", psiClass))
                            changeBindingStatement(butterKnifeBindMethod, statement, elementFactory.createStatementFromText("View view = ${statement.text.trim().split("=")[1]}", psiClass))
                        } else { // 没有局部变量，那就代表有全局变量
                            addMethodStatement(butterKnifeBindMethod, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(${array[0]});", psiClass))
                        }
                    } else { // 没有变量，则手动添加一个局部变量
                        addMethodStatement(butterKnifeBindMethod, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(view);", psiClass))
                        changeBindingStatement(butterKnifeBindMethod, statement, elementFactory.createStatementFromText("View view = ${statement.text}", psiClass))
                    }
                } else if (statement.text.trim().contains("ButterKnife.bind(")) {
                    addBindViewListStatement(butterKnifeBindMethod, statement)
                    return@jump
                }
            }
        }

        psiClass.methods.forEach {
            it.body?.statements?.forEach { statement ->
                changeBindViewStatement(statement)
            }
        }

        // 内部类也可能使用外部类的变量
        psiClass.innerClasses.forEach {
            it.methods.forEach { method ->
                method.body?.statements?.forEach { statement ->
                    changeBindViewStatement(statement)
                }
            }
        }
    }

    private fun findButterKnifeBindMethod(): PsiMethod? {
        var resultMethod: PsiMethod? = null
        run jump@{
            psiClass.methods.forEach { method ->
                method.body?.statements?.forEach { statement ->
                    if (statement.text.trim().contains("ButterKnife.bind(")) {
                        if (method.isConstructor) {
                            resultMethod = method
                            return@jump
                        }
                    }
                }
            }
        }
        return resultMethod
    }

    private fun findLayoutMethod(): PsiMethod? {
        var resultMethod: PsiMethod? = null
        run jump@{
            psiClass.methods.forEach { method ->
                method.body?.statements?.forEach { statement ->
                    if (statement.text.contains("R.layout")) {
                        resultMethod = method
                        return@jump
                    }
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