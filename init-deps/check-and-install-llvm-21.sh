#!/bin/bash

# 1. 获取 Clang 主版本号
# 如果没安装 clang，这里会报错，所以加个简单的判断
if ! command -v clang &> /dev/null; then
    CLANG_VERSION_MAJOR=0
else
    CLANG_VERSION_MAJOR=$(clang -dumpversion | cut -f1 -d.)
fi

INSTALL_FLAG="./llvm21_installed.done"

echo "Current clang version is [$CLANG_VERSION_MAJOR]"

# 2. 检查版本是否小于 15
if [ "$CLANG_VERSION_MAJOR" -lt 15 ]; then
	if [ -f /usr/bin/clang-21 ]; then
		echo "LLVM 21 is already installed."
		exit 0
	else
		echo "LLVM 21 is not installed. Proceeding with installation..."
	fi
    
	# 执行清理和安装逻辑
	# sudo apt remove -y llvm llvm-dev
	
	echo "Downloading LLVM script..."
	wget 'https://apt.llvm.org/llvm.sh'
	chmod +x llvm.sh
	
	echo "Installing LLVM 21..."
	sudo ./llvm.sh 21
	
	# 验证安装情况
	echo "Checking installation paths:"
	ls /usr/bin | grep clang | tr '\n' ' '
	ls /usr/lib | grep llvm | tr '\n' ' '
	ls /usr/lib/clang
	sudo update-alternatives --install /usr/bin/clang clang /usr/bin/clang-21 100
	sudo update-alternatives --install /usr/bin/clang++ clang++ /usr/bin/clang++-21 100
	sudo update-alternatives --set clang /usr/bin/clang-21
	sudo update-alternatives --set clang++ /usr/bin/clang++-21
	echo "clang-21 path: $(which clang-21)"
	echo "default clang path: $(which clang)"
	echo "all clang $(which -a clang)"
	# # 3. 标记安装完成
	# touch "$INSTALL_FLAG"
fi
