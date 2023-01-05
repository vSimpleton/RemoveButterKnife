package com.imiyar.removebutterknife.parser

import com.imiyar.removebutterknife.utils.getLayoutRes
import com.imiyar.removebutterknife.utils.underLineToHump
import com.imiyar.removebutterknife.utils.withViewBinding
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile

class FragmentCodeParser(project: Project, private val vFile: VirtualFile, psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) :
    BaseCodeParser(project, psiJavaFile, psiClass) {

    init {
        findBindViewAnnotation()
        findOnClickAnnotation()
    }

    override fun findViewInsertAnchor() {
        var layoutRes = ""
        var bindingName = ""
        val onCreateViewMethod = psiClass.findMethodsByName("onCreateView", false)[0]
        // 需要删除的语句锚点有：inflater.inflate( 、 return
        onCreateViewMethod.body?.statements?.forEach { statement ->
            if (statement.firstChild.text.trim().contains("R.layout.")) {
                layoutRes = statement.firstChild.text.trim().getLayoutRes()
                // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                bindingName = layoutRes.underLineToHump().withViewBinding()
                addBindingField("private $bindingName mBinding;\n")
                addImportStatement(vFile, layoutRes)
                return@forEach
            }
        }

        onCreateViewMethod.body?.statements?.forEach { statement ->
            if (statement.firstChild.text.trim().contains("inflater.inflate(")) {
                val bindingStatement = elementFactory.createStatementFromText("mBinding = $bindingName.inflate(${onCreateViewMethod.parameterList.parameters[0].name}, ${onCreateViewMethod.parameterList.parameters[1].name}, false);", psiClass)
                changeBindingStatement(onCreateViewMethod, statement, bindingStatement)
            } else if (statement.firstChild.text.trim().contains("return")) {
                val returnStatement = elementFactory.createStatementFromText("return mBinding.getRoot();", psiClass)
                changeBindingStatement(onCreateViewMethod, statement, returnStatement)
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

    override fun findClickInsertAnchor() {
        val onViewCreatedMethod = psiClass.findMethodsByName("onViewCreated", false)[0]
        insertOnClickMethodByVB(onViewCreatedMethod)
    }

}