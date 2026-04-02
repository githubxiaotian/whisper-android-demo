# 指定 CMake 最低版本要求
cmake_minimum_required(VERSION 3.22.1)

# 定义项目名称
project("whisperdemo")

# 设置 C++ 标准为 C++17，并要求必须支持
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 添加编译器优化标志
# -O3: 最高级别优化
# -DNDEBUG: 禁用断言和调试代码
# -fPIC: 生成位置无关代码（共享库必需）
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -DNDEBUG -fPIC")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -DNDEBUG -fPIC")

# 禁用 whisper 的测试功能，减少编译时间和文件大小
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
# 禁用 whisper 示例程序
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
# 禁用 whisper 服务器功能（不需要 HTTP 服务）
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

# 使用 FetchContent 模块从 GitHub 动态下载 whisper.cpp 源码
#[=[include(FetchContent)
    FetchContent_Declare(
        whisper
        GIT_REPOSITORY https://github.com/githubxiaotian/whisper.cpp.git  # Git 仓库地址
        GIT_TAG        master                                         # 使用 master 分支（可改为具体版本号如 v1.6.0）
        GIT_SHALLOW    TRUE                                           # 浅克隆，只获取最近一次提交，加快下载速度
        GIT_PROGRESS   TRUE                                           # 显示下载进度
    )]=]

add_subdirectory(${CMAKE_SOURCE_DIR}/whisper.cpp)

# 禁用 AVX 指令集（避免在某些 ARM 设备上不兼容）
set(WHISPER_NO_AVX ON CACHE BOOL "" FORCE)
# 禁用 AVX2 指令集
set(WHISPER_NO_AVX2 ON CACHE BOOL "" FORCE)
# 禁用 FMA 指令集
set(WHISPER_NO_FMA ON CACHE BOOL "" FORCE)
# 禁用 F16C 指令集
set(WHISPER_NO_F16C ON CACHE BOOL "" FORCE)

# 下载并构建 whisper 库
# FetchContent_MakeAvailable(whisper)

# 创建共享库（.so 文件），包含两个源文件
add_library(whisperdemo SHARED
    native-lib.cpp      # 主要的 JNI 实现文件
    audio_utils.cpp     # 音频处理工具函数
)

# 查找 Android 系统的 log 库（用于 __android_log_print 等日志输出）
find_library(log-lib log)

# 设置 whisperdemo 库的头文件搜索路径
target_include_directories(whisperdemo PRIVATE
    ${whisper_SOURCE_DIR}/include  # whisper 的 include 目录
    ${whisper_SOURCE_DIR}          # whisper 的根目录
)

# 如果存在 ggml/include 目录（新版本 whisper.cpp 结构），也添加到搜索路径
if(EXISTS "${whisper_SOURCE_DIR}/ggml/include")
    target_include_directories(whisperdemo PRIVATE ${whisper_SOURCE_DIR}/ggml/include)
endif()

# 如果存在 ggml 目录，也添加到搜索路径
if(EXISTS "${whisper_SOURCE_DIR}/ggml")
    target_include_directories(whisperdemo PRIVATE ${whisper_SOURCE_DIR}/ggml)
endif()

# 链接所需的库
target_link_libraries(whisperdemo
    whisper           # whisper.cpp 语音识别库
    ${log-lib}        # Android 系统日志库
    android           # Android NDK 基础库
)