#!/bin/sh

# cmd not support from wsl path
powershell.exe -NoProfile -Command "Start-Process 'D:\\sufer_win\\surfer.exe' build/wave.fst"
