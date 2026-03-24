echo "Usage: source env.sh"

# must be absolute path
ROOT_PATH=/workspaces/codespaces-blank

if [ ! -d "$ROOT_PATH" ]; then
  echo "Error: ROOT_PATH $ROOT_PATH does not exist. Please set it to a valid directory."
  return 1
fi

echo "Setting up environment variables for the project..."

JYD_HOME=$(realpath $ROOT_PATH/ysyx-workbench)

export JYD_AM_HOME=$JYD_HOME/abstract-machine
export JYD_NEMU_HOME=$JYD_HOME/nemu
export JYD_NPC_HOME=$JYD_HOME/npc
export SOC_HOME=$JYD_HOME/ysyxSoC
export NVBOARD_HOME=$JYD_HOME/nvboard
