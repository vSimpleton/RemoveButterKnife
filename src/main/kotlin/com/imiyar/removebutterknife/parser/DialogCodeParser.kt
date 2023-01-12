package com.imiyar.removebutterknife.parser

import com.imiyar.removebutterknife.utils.getBracketContent
import com.imiyar.removebutterknife.utils.getLayoutRes
import com.imiyar.removebutterknife.utils.underLineToHump
import com.imiyar.removebutterknife.utils.withViewBinding
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiStatement

class DialogCodeParser(project: Project, private val vFile: VirtualFile, psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) : BaseCodeParser(project, psiJavaFile, psiClass) {

    private var resultMethod: PsiMethod? = null
    private var resultStatement: PsiStatement? = null

    init {
        findBindViewAnnotation()
        findOnClickAnnotation()
    }

    override fun findViewInsertAnchor() {
        findMethodByButterKnifeBind()
        psiClass.methods.forEach { method ->
            method?.body?.statements?.forEach { statement ->
                if (statement.text.trim().contains("R.layout.")) {
                    val layoutRes = statement.text.trim().getLayoutRes()
                    // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                    val bindingName = layoutRes.underLineToHump().withViewBinding()
                    addBindingField("private $bindingName mBinding;\n")
                    addImportStatement(vFile, layoutRes)

                    resultMethod?.let { method ->
                        resultStatement?.let { statement ->
                            val params = statement.text.trim().getBracketContent()
                            if (params.contains(",")) {
                                addMethodStatement(method, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(${params.split(",")[1]});", psiClass))
                            } else {
                                addMethodStatement(method, statement, elementFactory.createStatementFromText("mBinding = $bindingName.bind(${params});", psiClass))
                            }
                        }
                    }
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
            insertOnClickMethodByVB(it)
        }
    }

}