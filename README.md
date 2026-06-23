# LED Audio Visualizer Controller (Android App) 📱✨

## 📌 Project Overview
This repository contains the source code for a Native Android application designed to remotely control and monitor an STM32-based Audio-Reactive RGB LED Visualizer[cite: 5]. Built with **Kotlin** and **Android Studio**, the app establishes a real-time Bluetooth Classic (SPP) connection to send control commands and receive live color states, creating a synchronized "virtual LED strip" directly on the smartphone screen[cite: 5, 6].

## 🚀 Key Features & Implementation
* **Bluetooth SPP Communication:** Implements a robust, multi-threaded Bluetooth Classic connection architecture using the standard SPP UUID (`00001101-0000-1000-8000-00805F9B34FB`)[cite: 5]. It includes an auto-reconnect mechanism to ensure seamless user experience when switching hardware modes[cite: 5].
* **Real-time Bi-directional Sync:** The app continuously parses incoming byte packets (Header: `0xAA`, Footer: `0x55`) from the STM32 to decode the live state of 60 LED pixels[cite: 5].
* **Custom UI Component:** Features a highly optimized, custom-drawn View (`LEDVisualizerView.kt`) using Android's `Canvas` API[cite: 6]. It renders 60 LEDs with dynamic glow effects via `RadialGradient` shaders, running efficiently at ~30 FPS[cite: 5, 6].
* **Interactive Controls:** 
  * Buttons to switch between 4 audio-reactive effects[cite: 5].
  * Manual beat trigger button (with hold-to-repeat functionality for continuous rainbow waves)[cite: 5].
  * Auto-calibration command trigger with automated progress dialogs[cite: 5].

## 🛠️ Tech Stack
* **IDE:** Android Studio
* **Language:** Kotlin[cite: 5]
* **Key APIs:** Android Bluetooth API, Custom View/Canvas API (`android.graphics.Canvas`, `Paint`, `Shader`)[cite: 5, 6]

## 📁 Repository Structure
* `/app/src/main/java/com/example/ledfxcontroller/`
  * `MainActivity.kt` - Core logic, Bluetooth threading, and UI interaction[cite: 5].
  * `LEDVisualizerView.kt` - Custom rendering engine for the virtual LED strip[cite: 6].
* `/app/src/main/res/` - Layout XMLs, drawables, and UI resources[cite: 3].

Demo link 
https://drive.google.com/file/d/10tn8bVEcqwVPmf9QI0IvC2CE0BbwYoNW/view?usp=drive_link
---
*Developed by Ho Ngoc Thien Phuoc.*
