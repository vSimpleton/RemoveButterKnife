package com.imiyar.removebutterknife.parser

import com.google.gson.JsonParser
import com.imiyar.removebutterknife.utils.getAnnotationIds
import com.imiyar.removebutterknife.utils.isOnlyContainsTarget
import com.imiyar.removebutterknife.utils.underLineToHump
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

open class BaseCodeParser(private val project: Project, private val psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) {

    private val bindViewFieldLists = mutableListOf<Pair<String, String>>()
    private val onClickMethodLists = mutableListOf<Pair<String, String>>()
    private val onLongClickMethodLists = mutableListOf<Pair<String, String>>()
    private val onTouchMethodLists = mutableListOf<Pair<String, String>>()
    protected val innerBindViewFieldLists = mutableListOf<Pair<String, String>>()

    val elementFactory = JavaPsiFacade.getInstance(project).elementFactory

    fun execute() {
        findViewInsertAnchor()
        findClickInsertAnchor()
        deleteButterKnifeBindStatement()
    }

    /**
     * 遍历所有字段并找到@BindView注解
     * @param isDelete 是否删除@BindView注解的字段 true -> 删除字段  false -> 仅删除注解
     */
    fun findBindViewAnnotation(isDelete: Boolean = true) {
        psiClass.fields.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val first = psiAnnotation.findAttributeValue("value")?.lastChild?.text.toString()
                    val second = it.name
                    if (isDelete) {
                        bindViewFieldLists.add(Pair(first, second))
                    } else {
                        innerBindViewFieldLists.add(Pair(first, second))
                    }

                    writeAction {
                        if (isDelete) {
                            it.delete()
                        } else {
                            psiAnnotation.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历所有方法并找到@OnClick / @OnLongClick / @OnTouch注解
     */
    fun findOnClickAnnotation() {
        psiClass.methods.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("OnClick") == true || psiAnnotation.qualifiedName?.contains("OnLongClick") == true || psiAnnotation.qualifiedName?.contains("OnTouch") == true) {
                    psiAnnotation.findAttributeValue("value")?.text?.getAnnotationIds()?.forEach { id ->
                        var second = "${it.name}()"
                        if (it.parameters.isNotEmpty()) {
                            // TODO 需要考虑多参数的情况，比如OnTouch有两个参数（累了，晚点考虑）
                            second = "${it.name}(view)"
                        }
                        if (psiAnnotation.qualifiedName?.contains("OnClick") == true) {
                            onClickMethodLists.add(Pair(id, second))
                        } else if (psiAnnotation.qualifiedName?.contains("OnLongClick") == true) {
                            onLongClickMethodLists.add(Pair(id, second))
                        } else if (psiAnnotation.qualifiedName?.contains("OnTouch") == true) {
                            onTouchMethodLists.add(Pair(id, second))
                        }
                    }

                    writeAction {
                        // 删除@OnClick注解
                        psiAnnotation.delete()
                    }
                }
            }
        }

    }

    /**
     * 添加mBinding变量
     */
    protected fun addBindingField(fieldStr: String) {
        psiClass.addAfter(elementFactory.createFieldFromText(fieldStr, psiClass), psiClass.allFields.last())
    }

    /**
     * 修改mBinding的初始化语句
     * @param method 需要修改的语句所在的方法
     * @param beforeStatement 修改前的语句
     * @param afterStatement 修改后的语句
     */
    protected fun changeBindingStatement(method: PsiMethod, beforeStatement: PsiStatement, afterStatement: PsiStatement) {
        writeAction {
            method.addAfter(afterStatement, beforeStatement)
            beforeStatement.delete()
        }
    }

    /**
     * 在方法中添加一个新的statement
     */
    protected fun addMethodStatement(method: PsiMethod, statement: PsiStatement, newStatement: PsiStatement) {
        writeAction {
            method.addAfter(newStatement, statement)
        }
    }

    /**
     * 添加import语句
     * @param layoutRes 布局文件
     */
    protected fun addImportStatement(vFile: VirtualFile, layoutRes: String) {
        val importList = psiJavaFile.importList
        val importStatement = elementFactory.createImportStatementOnDemand(getBindingJsonFile(vFile, layoutRes))
        writeAction {
            importList?.add(importStatement)
        }
    }

    /**
     * 把原本使用@BindView的属性修改为mBinding.xxx
     * @param psiStatement 需要修改的statement
     */
    protected fun changeBindViewStatement(psiStatement: PsiStatement) {
        var replaceText = psiStatement.text.trim()
        bindViewFieldLists.forEachIndexed { index, pair ->
            if (replaceText.isOnlyContainsTarget(pair.second) && !replaceText.isOnlyContainsTarget("R.id.${pair.second}")) {
                replaceText = replaceText.replace("\\b${pair.second}\\b".toRegex(), "mBinding.${pair.first.underLineToHump()}")
            }
            if (index == bindViewFieldLists.size - 1) {
                if (replaceText != psiStatement.text.trim()) {
                    val replaceStatement = elementFactory.createStatementFromText(replaceText, psiClass)
                    writeAction {
                        psiStatement.addAfter(replaceStatement, psiStatement)
                        psiStatement.delete()
                    }
                }
            }
        }
    }

    /**
     * 插入initListener方法(ViewBinding使用)
     * @param psiMethod 需要插入的地方（如：Activity的onCreate、Fragment的oonViewCreated）
     */
    protected fun insertOnClickMethodByVB(psiMethod: PsiMethod) {
        val psiMethods = psiClass.findMethodsByName("initListener", false)
        if (psiMethods.isEmpty() && (onClickMethodLists.isNotEmpty() || onLongClickMethodLists.isNotEmpty())) {
            val createMethod = elementFactory.createMethodFromText("private void initListener() {}\n", psiClass)
            val psiStatement = elementFactory.createStatementFromText("initListener();", psiClass)
            writeAction {
                psiMethod.addAfter(psiStatement, psiMethod.body?.statements?.last())
                psiClass.addAfter(createMethod, psiMethod)
                insertOnClickStatement()
            }
        }
    }

    /**
     * 在initListener中插入setOnClickListener语句
     */
    private fun insertOnClickStatement() {
        val listenerMethod = psiClass.findMethodsByName("initListener", false)[0]
        onClickMethodLists.forEach { pair ->
            listenerMethod.lastChild.add(elementFactory.createStatementFromText(getOnClickStatement(pair), psiClass))
        }
        onLongClickMethodLists.forEach { pair ->
            listenerMethod.lastChild.add(elementFactory.createStatementFromText(getOnLongClickStatement(pair), psiClass))
        }
        onTouchMethodLists.forEach { pair ->
            listenerMethod.lastChild.add(elementFactory.createStatementFromText(getOnTouchStatement(pair), psiClass))
        }
    }

    /**
     * 使用findViewById插入的@OnClick监听
     */
    protected fun insertOnClickMethodByFVB(psiMethod: PsiMethod, parameterName: String) {
        onClickMethodLists.forEach { pair ->
            psiMethod.lastChild.add(elementFactory.createStatementFromText(getOnClickStatement(pair, parameterName), psiClass))
        }
        onLongClickMethodLists.forEach { pair ->
            psiMethod.lastChild.add(elementFactory.createStatementFromText(getOnLongClickStatement(pair, parameterName), psiClass))
        }
        onTouchMethodLists.forEach { pair ->
            psiMethod.lastChild.add(elementFactory.createStatementFromText(getOnTouchStatement(pair, parameterName), psiClass))
        }
    }

    /**
     * 删除ButterKnife的import语句、绑定语句、解绑语句
     */
    private fun deleteButterKnifeBindStatement() {
        writeAction {
            psiJavaFile.importList?.importStatements?.forEach {
                if (it.qualifiedName?.lowercase()?.contains("butterknife") == true) {
                    it.delete()
                }
            }

            psiClass.methods.forEach {
                it.body?.statements?.forEach { statement ->
                    if (statement.firstChild.text.trim().contains("ButterKnife.bind(")) {
                        statement.delete()
                    }
                }
            }

            val unBinderField = psiClass.fields.find {
                it.type.canonicalText.contains("Unbinder")
            }
            if (unBinderField != null) {
                psiClass.methods.forEach {
                    it.body?.statements?.forEach { statement ->
                        if (statement.firstChild.text.trim().contains(unBinderField.name)) {
                            statement.delete()
                        }
                    }
                }
                unBinderField.delete()
            }
        }
    }

    /**
     * 插入的@OnClcik替代语句
     */
    private fun getOnClickStatement(pair: Pair<String, String>, parameterName: String = ""): String {
        return if (parameterName.isNotEmpty()) {
            "$parameterName.findViewById(R.id.${pair.first}).setOnClickListener(view -> ${pair.second});\n"
        } else {
            "mBinding.${pair.first.underLineToHump()}.setOnClickListener(view -> ${pair.second});\n"
        }
    }

    /**
     * 插入的@OnLongClcik替代语句
     */
    private fun getOnLongClickStatement(pair: Pair<String, String>, parameterName: String = ""): String {
        return if (parameterName.isNotEmpty()) {
            "$parameterName.findViewById(R.id.${pair.first}).setOnLongClickListener(view -> {\n" +
                    "${pair.second};\n" +
                    "return false;\n" +
                    "});\n"
        } else {
            "mBinding.${pair.first.underLineToHump()}.setOnLongClickListener(view -> {\n" +
                    "${pair.second};\n" +
                    "return false;\n" +
                    "});\n"
        }
    }

    /**
     * 插入的@OnTouch替代语句
     */
    private fun getOnTouchStatement(pair: Pair<String, String>, parameterName: String = ""): String {
        return if (parameterName.isNotEmpty()) {
            "$parameterName.findViewById(R.id.${pair.first}).setOnTouchListener((view, event) -> {\n" +
                    "${pair.second};\n" +
                    "return false;\n" +
                    "});\n"
        } else {
            "mBinding.${pair.first.underLineToHump()}.setOnTouchListener((view, event) -> {\n" +
                    "${pair.second};\n" +
                    "return false;\n" +
                    "});\n"
        }
    }

    /**
     * 获取生成的Binding映射文件，并拿到该Binding类的包路径
     */
    private fun getBindingJsonFile(vFile: VirtualFile, layoutRes: String): String {
        val listFilesName = mutableListOf<String>()
        var fileName = vFile.parent
        while (!fileName.toString().endsWith("src")) {
            fileName = fileName.parent
        }

        var bindImportClass = ""
        var moduleFile = File("${fileName.parent.path}/build/intermediates/data_binding_base_class_log_artifact/debug/out/")
        if (moduleFile.isDirectory) {
            if (moduleFile.listFiles() != null && moduleFile.listFiles().isNotEmpty()) {
                moduleFile = moduleFile.listFiles()[0]
            }
        }
        if (moduleFile.isFile) {
            val jsonObject = JsonParser.parseString(readJsonFile(moduleFile)).asJsonObject
            if (jsonObject.get("mappings").asJsonObject.get(layoutRes) != null) {
                bindImportClass = jsonObject.get("mappings").asJsonObject.get(layoutRes).asJsonObject.get("module_package").asString
            }
        }

        // 优先判断该文件所属的module下的json映射文件，若找不到需要的Binding类，再遍历所有module寻找json文件
        if (bindImportClass.isNotEmpty()) {
            return bindImportClass
        } else {
            val dataBindingDir = "${fileName.parent.parent.path}${File.separator}"
            val file = File(dataBindingDir)
            if (file.isDirectory) {
                file.listFiles()?.forEach {
                    if (it.isDirectory && !it.isHidden) {
                        listFilesName.add(it.absolutePath)
                    }
                }
            }

            listFilesName.forEach {
                val fileStr = "$it/build/intermediates/data_binding_base_class_log_artifact/debug/out/"
                var jsonFile = File(fileStr)
                if (jsonFile.isDirectory) {
                    if (jsonFile.listFiles() != null && jsonFile.listFiles().isNotEmpty()) {
                        jsonFile = jsonFile.listFiles()[0]
                    }
                }
                // 拿到json文件并拿到我们需要的Binding类包名，用于添加import
                if (jsonFile.isFile) {
                    val jsonObject = JsonParser.parseString(readJsonFile(jsonFile)).asJsonObject
                    if (jsonObject.get("mappings").asJsonObject.get(layoutRes) != null) {
                        bindImportClass = jsonObject.get("mappings").asJsonObject.get(layoutRes).asJsonObject.get("module_package").asString
                    }
                }
                if (bindImportClass.isNotEmpty()) {
                    return@forEach
                }
            }
        }

        return bindImportClass
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


    private fun writeAction(commandName: String = "RemoveButterKnifeWriteAction", runnable: Runnable) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterKnifeGroupID", runnable, psiJavaFile)
    }

    // 寻找viewBinding插入的锚点
    open fun findViewInsertAnchor() {}

    // 寻找clickListener插入的锚点
    open fun findClickInsertAnchor() {}

}