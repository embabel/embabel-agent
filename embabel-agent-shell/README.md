# Embabel Agent Shell Module

Interactive Spring Shell experience for the Embabel Agent platform.

## Overview
This module provides a terminal-based interface for interacting with the Embabel Agent platform, built on Spring Shell framework. It offers interactive commands for agent management, chat sessions, and system operations.

## Key Components

### Shell Commands (`ShellCommands.kt`)
- **Agent Management**: List and interact with available agents
- **Chat Interface**: Start interactive chat sessions with agents
- **System Operations**: Platform status and configuration commands

### Terminal Services (`TerminalServices.kt`)
- **User Input Handling**: Process user interactions and confirmations
- **Process Management**: Handle waiting processes and user responses
- **Output Formatting**: Format and display agent responses

### Chat Session Implementation
- **LastMessageIntentAgentPlatformChatSession**: Shell-specific chat session implementation

### Personality Providers
- **Multi-personality Support**: StarWars, Severance, Hitchhiker, Colossus personalities
- **Prompt Customization**: Personality-specific prompts and formatting

## Configuration
Shell behavior is configured through:
- **ShellConfiguration**: Primary Spring configuration for shell module beans
- **ShellProperties**: Configuration properties for shell behavior settings
  - Line length settings
  - Chat confirmation preferences
  - Conversation binding options

## Usage
Interactive shell commands are available when running the application in shell mode. None of these classes are intended for use outside of the shell context.