#!/bin/bash

# no pull
# if [ -d /home/runner/work/ysyx-submit-test/ ]; then
# echo "Running in GitHub Actions environment, updating self repo"
# git config pull.rebase false
# git pull
# fi

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
	sudo ./llvm.sh 21 > /dev/null
	
	# 验证安装情况
	echo "Checking installation paths:"
	ls /usr/bin | grep clang | tr '\n' ' '
	ls /usr/lib | grep llvm | tr '\n' ' '
	ls /usr/lib/clang
	sudo update-alternatives --install /usr/bin/clang clang /usr/bin/clang-21 100
	sudo update-alternatives --install /usr/bin/clang++ clang++ /usr/bin/clang++-21 100
	# must set
	sudo update-alternatives --set clang /usr/bin/clang-21
	sudo update-alternatives --set clang++ /usr/bin/clang++-21
	echo "clang-21 path: $(which clang-21)"
	echo "default clang path: $(which clang)"
	echo "all clang $(which -a clang)"

	echo "g++ version: $(g++ -dumpversion)"
	echo "Installing g++-13 for new stdlib"
	sudo add-apt-repository ppa:ubuntu-toolchain-r/test -y > /dev/null
	sudo apt-get update > /dev/null
  sudo apt-get install g++-13 gcc-riscv64-linux-gnu binutils-riscv64-linux-gnu -y > /dev/null
	sudo update-alternatives --install /usr/bin/g++ g++ /usr/bin/g++-13 100
	sudo update-alternatives --set g++ /usr/bin/g++-13
	echo "current g++ version: $(g++ -dumpversion)"

	# no need
	# 	sudo ln -sf /usr/bin/clang-21 /usr/bin/clang
	# 	sudo ln -sf /usr/bin/clang++-21 /usr/bin/clang++
	# 	echo "After ln, default clang path: $(which clang)"
	#

	# # 3. 标记安装完成
	# touch "$INSTALL_FLAG"
fi
