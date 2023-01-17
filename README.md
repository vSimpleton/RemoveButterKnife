## RemoveButterKnife

实现去除ButterKnife并转换成ViewBinding，目前仅支持Java语言。

**注：由于不同的类中对于布局的引用方法过于杂乱，无法统一修改，所以只有在Activity、Fragment、自定义View中才支持把ButterKnife转换成ViewBinding，
而对于其他类（如ViewHolder、Dialog则是直接改成findViewById形式）。**

### 使用方法
#### 安装插件
本人太懒，暂时不生成插件使用，有需要的可以先直接拉代码运行，运行后会直接打开AS，选择自己要操作的项目即可。

#### 开启ViewBinding

```groovy
android {
        viewBinding {
            enabled = true
        }
    }
```

#### 生成ViewBinding相关的类
在项目目录下执行`./gradlew dataBindingGenBaseClassesDebug`生成ViewBinding相关的类与映射文件

#### 执行代码转换
右键需要转换的文件目录（支持单个文件操作或多级目录操作），点击RemoveButterKnife开始转换