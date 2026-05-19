#!/bin/bash

echo "Starting concurrent Claude Code tasks..."

sleep 10m
wait

# Fork: Run headless prompts concurrently and save to temp files
claude -p "context in IMPLEMENTATION.md, can you remove the fade transition that happens when swiping up on the pill, it should be completely seamless, and the animation should be just as smooth when the user swipes up a tiny bit when making a swipe to switch tabs (just like when switching apps on an iphone). Also, the position of the tab being swiped up on relative to the user's finger should stay the same as the window is scaling down, right now it gets pushed towards the side of the 2 columns it wants to be in instead of tracking the finger until released and then going to its side of the columns." --bare --allowedTools "Bash,Read,Edit,Glob,Grep" > prompt1.txt
claude -p "context in IMPLEMENTATION.md, can you add this app to the share sheet for links so i can open it by sharing the link from another app" --bare --allowedTools "Bash,Read,Edit,Glob,Grep" > prompt2.txt
claude -p "context in IMPLEMENTATION.md, can you add this app as a browser app, so i can set it as my default browser" --bare --allowedTools "Bash,Read,Edit,Glob,Grep" > prompt3.txt

# Synchronize: Wait for all background processes to finish
wait

echo "Tasks completed."
