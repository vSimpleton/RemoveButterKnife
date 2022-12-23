package com.imiyar.removebutterknife

import com.google.gson.JsonParser
import com.imiyar.removebutterknife.utils.getLayoutRes
import com.imiyar.removebutterknife.utils.isOnlyContainsTarget
import com.imiyar.removebutterknife.utils.underLineToHump
import com.imiyar.removebutterknife.utils.withViewBinding
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.*

class ButterActionDelegate(private val project: Project, private val vFile: VirtualFile, private val psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) {

    private val elementFactory = JavaPsiFacade.getInstance(project).elementFactory

    /**
     * 存储所有使用@BindView注解的变量名称，示例Pair<xml属性名称，class变量名称>
     */
    private val bindViewFieldsLists = mutableListOf<Pair<String, String>>()

    /**
     * 存储所有使用@OnClick注解的方法名称，示例Pair<xml属性名称，方法名称(参数)>
     */
    private val onClickMethodsLists = mutableListOf<Pair<String, String>>()
    private val clickStatementsLists = mutableListOf<PsiElement>()

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }

        // 新增Binding类实例并写进class的全局变量里
        // 并把setContentView()修改为使用setContentView(mBinding.root)，注意：区分Activity跟Fragment
        addViewBindingStatement()

        // 遍历保存所有使用@BindView注解的变量名称
        findAllBindViewAnnotation()

        // 遍历保存所有使用@OnClick注解的属性id以及对应的click方法
        findAllOnClickMethodAnnotation()

        // 把原本使用@BindView的变量xxx，改成mBinding.xxx(驼峰式命名)
        changeAllBindViewFields()

        // 把原本使用@OnClick注解的方法改为常规的ClickListener
        addAllOnClickMethods()

        // 删除ButterKnife的import语句、绑定语句、解绑语句
        deleteButterKnifeBindStatement()

        return true
    }

    /**
     * 把原本使用@OnClick注解的方法改为常规的ClickListener
     */
    private fun addAllOnClickMethods() {
        // TODO 需要考虑出Activity之外的其他类
        val psiMethods = psiClass.findMethodsByName("onCreate", false)
        if (psiMethods.isNotEmpty()) {
            val psiStatement = elementFactory.createStatementFromText("initListener();", psiMethods[0])
            val createMethod = elementFactory.createMethodFromText("private void initListener() {}\n", psiClass)

            writeAction {
                onClickMethodsLists.forEach {
                    val statementStr = "mBinding.${it.first.underLineToHump()}.setOnClickListener(view -> ${it.second});\n"
                    val statement = elementFactory.createStatementFromText(statementStr, psiClass)
                    createMethod.lastChild.add(statement)
                }

                psiMethods[0].addAfter(psiStatement, psiMethods[0].body?.statements?.last())
                psiClass.addAfter(createMethod, psiMethods[0])
            }
        }
    }

    /**
     * 修改setContentView语句
     */
    private fun changeSetContentViewStatement(bindingName: String) {
        psiClass.methods.forEach {
            if (it.name == "onCreate") {
                it.body?.statements?.forEach { statement ->
                    if (statement.firstChild.text.trim().contains("setContentView(")) {
                        val bindingStatement = elementFactory.createStatementFromText("setContentView(mBinding.getRoot());", it)
                        writeAction {
                            it.addBefore(bindingStatement, statement)
                            statement.delete()
                        }
                    }
                }
            } else if (it.name == "onCreateView") {
                // 需要删除的语句锚点有：inflater.inflate( 、 return
                it.body?.statements?.forEach { statement ->
                    if (statement.firstChild.text.trim().contains("inflater.inflate(")) {
                        // TODO 需要先判断方法是否有参数
                        val bindingStatement = elementFactory.createStatementFromText("mBinding = $bindingName.inflate(${it.parameters[0].name}, ${it.parameters[1].name}, false);", it)
                        writeAction {
                            it.addBefore(bindingStatement, statement)
                            statement.delete()
                        }
                    } else if (statement.firstChild.text.trim().contains("return")) {
                        val returnStatement = elementFactory.createStatementFromText("return mBinding.getRoot();", it)
                        writeAction {
                            it.addBefore(returnStatement, statement)
                            statement.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * 添加ViewBinding的实例
     */
    private fun addViewBindingStatement() {
        var layoutRes = ""
        var psiField: PsiField? = null
        var bindingName = ""
        psiClass.methods.forEach {
            if (it.name == "onCreate" || it.name == "onCreateView") {
                it.body?.statements?.forEach { statement ->
                    // 拿到布局名称
                    if (statement.firstChild.text.trim().contains("R.layout.")) {
                        layoutRes = statement.firstChild.text.trim().getLayoutRes()
                        // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                        bindingName = layoutRes.underLineToHump().withViewBinding()
                        // 把转换后的Binding实例名称以及实例化语句添加进class类里
                        if (it.name =="onCreate") {
                            psiField = elementFactory.createFieldFromText("private $bindingName mBinding = $bindingName.inflate(getLayoutInflater());\n", psiClass)
                        } else if (it.name == "onCreateView") {
                            psiField = elementFactory.createFieldFromText("private $bindingName mBinding;\n", psiClass)
                        }
                    }
                }
            }
        }

        // 下面的操作用于获取Binding类生成的映射关系的目录，会拿到一个Json文件
        var fileName = vFile.parent
        while (!fileName.toString().endsWith("src")) {
            fileName = fileName.parent
        }
        // 这里的作用是为了区分不同的module拿到的文件路径
        val dataBindingDir = "${fileName.parent.path}/build/intermediates/data_binding_base_class_log_artifact/debug/out/"
        var jsonFile = File(dataBindingDir)
        if (jsonFile.isDirectory) {
            if (jsonFile.listFiles() != null && jsonFile.listFiles().isNotEmpty()) {
                jsonFile = jsonFile.listFiles()[0]
            }
        }
        // 拿到json文件并拿到我们需要的Binding类包名，用于添加import
        val jsonObject = JsonParser.parseString(readJsonFile(jsonFile)).asJsonObject
        val bindImportClass = jsonObject.get("mappings").asJsonObject.get(layoutRes).asJsonObject.get("module_package").asString

        val importList = psiJavaFile.importList
        val importStatement = elementFactory.createImportStatementOnDemand(bindImportClass)

        writeAction {
            psiField?.let {
                psiClass.addAfter(it, psiClass.allFields.last())
                importList?.add(importStatement)
            }
        }

        if (bindingName.isNotEmpty()) {
            changeSetContentViewStatement(bindingName)
        }
    }

    /**
     * 把viewBinding生成的映射文件解析成json字符串
     */
    private fun readJsonFile(jsonFile: File): String {
        val sb = StringBuffer()
         BufferedReader(InputStreamReader(FileInputStream(jsonFile), "UTF-16BE")).useLines { lines ->
             lines.forEach {
                 sb.append(it)
             }
        }
        return sb.toString()
    }

    /**
     * 遍历保存所有使用@BindView注解的变量名称
     */
    private fun findAllBindViewAnnotation() {
        psiClass.fields.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val first = psiAnnotation.findAttributeValue("value")?.lastChild?.text.toString()
                    val second = it.name
                    bindViewFieldsLists.add(Pair(first, second))

                    writeAction {
                        // 删除@BindView注解相关的字段
                        it.delete()
                    }
                }
            }
        }
    }

    /**
     * 把原本使用@BindView的变量xxx，改成mBinding.xxx(驼峰式命名)
     */
    private fun changeAllBindViewFields() {
        psiClass.methods.forEach {
            it.body?.statements?.forEach { statement ->
                var replaceText = statement.text.trim()
                bindViewFieldsLists.forEachIndexed { index, pair ->
                    if (replaceText.isOnlyContainsTarget(pair.second) && !replaceText.isOnlyContainsTarget("R.id.${pair.second}")) {
                        replaceText = replaceText.replace(pair.second, "mBinding.${pair.first.underLineToHump()}")
                    }
                    if (index == bindViewFieldsLists.size - 1) {
                        if (replaceText != statement.text.trim()) {
                            val replaceStatement = elementFactory.createStatementFromText(replaceText, it)
                            writeAction {
                                it.addBefore(replaceStatement, statement)
                                statement.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历保存所有使用@OnClick注解的属性id以及对应的click方法
     */
    private fun findAllOnClickMethodAnnotation() {
        psiClass.methods.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("OnClick") == true) {
                    val first = psiAnnotation.findAttributeValue("value")?.lastChild?.text.toString()
                    var second = "${it.name}()"
                    if (it.parameters.isNotEmpty()) {
                        second = "${it.name}(view)"
                    }
                    onClickMethodsLists.add(Pair(first, second))

                    writeAction {
                        // 删除@OnClick注解
                        psiAnnotation.delete()
                    }
                }
            }
        }
    }

    /**
     * 删除ButterKnife的import语句、绑定语句、解绑语句
     */
    private fun deleteButterKnifeBindStatement() {
        psiClass.methods.forEach {

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

    private fun writeAction(commandName: String = "RemoveButterKnifeWriteAction", runnable: Runnable) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterKnifeGroupID", runnable, psiJavaFile)
    }

}