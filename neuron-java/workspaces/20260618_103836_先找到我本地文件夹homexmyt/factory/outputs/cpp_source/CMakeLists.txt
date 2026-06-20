cmake_minimum_required(VERSION 3.10)
project(DistributedTaskQueue)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 查找线程库
find_package(Threads REQUIRED)

# 头文件目录
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# 源文件
set(SOURCES
    Task.cpp
    TaskQueue.cpp
    Worker.cpp
    DistributedTaskQueue.cpp
)

# 创建静态库
add_library(distributed_task_queue STATIC ${SOURCES})

# 链接线程库
target_link_libraries(distributed_task_queue PRIVATE Threads::Threads)

# 创建示例可执行文件
add_executable(task_queue_example example_usage.cpp)
target_link_libraries(task_queue_example PRIVATE distributed_task_queue)

# 安装规则
install(TARGETS distributed_task_queue
        LIBRARY DESTINATION lib
        ARCHIVE DESTINATION lib)

install(FILES 
    Task.h
    TaskQueue.h
    Worker.h
    DistributedTaskQueue.h
    DESTINATION include/distributed_task_queue
)