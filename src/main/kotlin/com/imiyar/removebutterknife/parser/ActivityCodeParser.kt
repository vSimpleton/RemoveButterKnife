package com.imiyar.removebutterknife.parser

import com.imiyar.removebutterknife.utils.getLayoutRes
import com.imiyar.removebutterknife.utils.underLineToHump
import com.imiyar.removebutterknife.utils.withViewBinding
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile

class ActivityCodeParser(
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
        // TODO 还需要考虑getLayoutId()的情况
        val onCreateMethod = psiClass.findMethodsByName("onCreate", false)[0]
        onCreateMethod.body?.statements?.forEach { statement ->
            if (statement.firstChild.text.trim().contains("R.layout.")) {
                val layoutRes = statement.firstChild.text.trim().getLayoutRes()
                // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                val bindingName = layoutRes.underLineToHump().withViewBinding()
                val afterStatement = elementFactory.createStatementFromText("setContentView(mBinding.getRoot());", psiClass)
                addBindingField("private $bindingName mBinding = $bindingName.inflate(getLayoutInflater());\n")
                changeBindingStatement(onCreateMethod, statement, afterStatement)
                addImportStatement(vFile, layoutRes)
            }
        }

        psiClass.methods.forEach {
            it.body?.statements?.forEach { statement ->
                changeBindViewStatement(statement)
            }
        }
    }

    override fun findClickInsertAnchor() {
        val onCreateMethod = psiClass.findMethodsByName("onCreate", false)[0]
        insertOnClickMethod(onCreateMethod)
    }
}