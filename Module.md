# Module b-language-server

Implement the Language Server Protocol for classical B and Event-B using Kotlin, lsp4j and ProB, whose probcli
ships with the ProB Java Kernel.

Clients (neovim, VS Code, ...) talk to the server over stdio (`--stdio`) or a TCP socket. The server turns their
requests into calls of the analysis of the model's kind, which runs in a shared ProB process. Animations run in a
ProB process of their own.

The source, issues and releases are on GitHub: [quoteme/b-language-server](https://github.com/quoteme/b-language-server).

## Clients

| Client | Editor | Project page |
| ------ | ------ | ------------ |
| ![neovim](https://cdn.jsdelivr.net/gh/devicons/devicon/icons/neovim/neovim-original.svg) b.nvim | Neovim | [github.com/Quoteme/b.nvim](https://github.com/Quoteme/b.nvim) |
| ![vscode](https://cdn.jsdelivr.net/gh/devicons/devicon/icons/vscode/vscode-original.svg) b.vscode | Visual Studio Code | [github.com/Quoteme/b.vscode](https://github.com/Quoteme/b.vscode) |

## How a request flows through the server

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant Client as Client (editor)
    participant Server as Server, BDocumentService,<br>BWorkspaceService
    participant Checks as DocumentChecks,<br>SettingsStore
    participant Open as OpenDocuments
    participant Dispatcher as AnalysisDispatcher
    participant Analysis as ClassicBAnalysis /<br>EventBAnalysis
    participant ProB as ProBSession

    Client->>Server: initialize
    Server-->>Client: capabilities

    User->>Client: open or save a file
    Client->>Server: didOpen / didSave
    Server->>Open: update(file, text)
    Server->>Checks: check(file)
    Checks->>Client: workspace/configuration ("prob")
    Client-->>Checks: Settings
    Checks->>Open: projectOf(file)
    Open-->>Checks: ClassicBProject or EventBProject
    Checks->>Dispatcher: check(project, settings)
    Dispatcher->>Analysis: project.match
    Analysis->>ProB: load, check, discharge POs
    ProB-->>Analysis: errors, warnings
    Analysis-->>Checks: Either: AnalysisFailure or problems
    Checks-->>Client: publishDiagnostics / showMessage

    User->>Client: hover or complete
    Client->>Server: hover / completion
    Server->>Dispatcher: hover / complete(project, position)
    Dispatcher->>Analysis: project.match
    Analysis-->>Server: Markdown / candidates
    Server-->>Client: Hover / CompletionList

    User->>Client: source action
    Client->>Server: codeAction
    Server-->>Client: the prob.* commands
    Client->>Server: workspace/executeCommand prob.*
    Server->>Dispatcher: Commands: modelCheck,<br>proofObligations, animationModel
    Dispatcher-->>Server: report, POs, animation model
    Server-->>Client: JSON result, diagnostics, messages

    Client->>Server: shutdown / exit
    Server->>ProB: close the ProB processes
```

## Components

```mermaid
graph TD
    client["Client<br>neovim, vscode, ..."]
    server["Server<br>Server, BDocumentService, BWorkspaceService"]
    config["config<br>Settings"]
    project["Project"]
    dispatcher["AnalysisDispatcher"]
    tasks["Analysis interfaces<br>Checker, HoverProvider, CompletionProvider,<br>ModelChecker, ProofObligationProvider, AnimationProvider"]
    classicBAnalysis["ClassicBAnalysis"]
    eventBAnalysis["EventBAnalysis"]
    classicB["ClassicBProject"]
    eventB["EventBProject"]
    classicFiles["files: .mch, .ref, .imp, ..."]
    eventFiles["Camille components: .mch<br>one machine or context per file"]
    probSession["ProBSession<br>one ProB process"]
    documentation["documentation tables<br>JSON from the Rodin Handbook<br>and the ProB Summary of B Syntax"]
    animation["Animation<br>its own ProB process"]

    client -->|"open, save, hover, completion,<br>code actions, prob commands"| server
    server -->|"diagnostics, hovers, completions,<br>messages, command results as JSON"| client
    server -->|"reads"| config

    server -->|"Project.of with the editor text"| project
    server -->|"check, hover, complete, modelCheck,<br>proofObligations, animationModel"| dispatcher
    dispatcher -->|"problems or a failure as Either, hovers,<br>completions, reports, proof obligations"| server

    dispatcher -->|"implements"| tasks
    classicBAnalysis -->|"implements"| tasks
    eventBAnalysis -->|"implements"| tasks

    dispatcher -->|"project.match"| classicBAnalysis
    dispatcher -->|"project.match"| eventBAnalysis

    project -->|"is"| classicB
    project -->|"is"| eventB
    classicB --> classicFiles
    eventB --> eventFiles

    classicB -->|"main file, declarations"| classicBAnalysis
    eventB -->|"parsed components, declarations"| eventBAnalysis

    classicBAnalysis -->|"load, check, model check"| probSession
    eventBAnalysis -->|"discharge proof obligations, model check"| probSession
    documentation -->|"hovers, completions"| classicBAnalysis
    documentation -->|"hovers, completions"| eventBAnalysis

    server -->|"prob.animate, prob.visualize"| animation
    animation -->|"loads the animation model of"| project
```

# Package b.language.server.server

The language server: it receives the client's LSP requests, asks the analysis, and answers the client.

```mermaid
classDiagram
    direction TB
    class LanguageServer {
        <<interface>>
    }
    class TextDocumentService {
        <<interface>>
    }
    class WorkspaceService {
        <<interface>>
    }
    class Server {
        +initialize(params) InitializeResult
        +shutdown()
        +exit()
    }
    class BDocumentService {
        +didOpen(params)
        +didChange(params)
        +didSave(params)
        +hover(params) Hover
        +completion(params) CompletionList
        +codeAction(params) List~CodeAction~
    }
    class BWorkspaceService {
        +executeCommand(params) Any
        +didChangeConfiguration(params)
    }
    class DocumentChecks {
        +check(file, adjust)
        +modelCheck(file)
    }
    class Commands {
        +execute(command, arguments) Any
    }
    class SettingsStore {
        +settingsOf(file) Settings
        +push(json)
    }
    class Command {
        <<enumeration>>
        MODEL_CHECK, DISCHARGE_POS, SHOW_POS
        OPEN_ANIMATION, ANIMATE, VISUALIZE
    }
    class OpenDocuments
    class Animation
    class AnalysisDispatcher
    class CommunicatorInterface {
        <<interface>>
    }

    LanguageServer <|.. Server
    Server *-- BDocumentService
    Server *-- BWorkspaceService
    TextDocumentService <|.. BDocumentService
    WorkspaceService <|.. BWorkspaceService
    BDocumentService --> OpenDocuments : update
    BDocumentService --> DocumentChecks : check on open and save
    BWorkspaceService --> Commands : executeCommand
    BWorkspaceService --> SettingsStore : push
    Commands --> Command
    Commands *-- Animation : prob.animate, prob.visualize
    Commands --> DocumentChecks : model check, discharge POs
    DocumentChecks --> SettingsStore : settingsOf
    DocumentChecks --> AnalysisDispatcher : check, modelCheck
    BDocumentService --> AnalysisDispatcher : hover, complete
    DocumentChecks --> CommunicatorInterface : publishDiagnostics
```

# Package b.language.server.client

How the server talks to the client on its own: diagnostics, messages and debug output.

```mermaid
classDiagram
    direction LR
    class CommunicatorInterface {
        <<interface>>
        +publishDiagnostics(target, diagnostics)
        +sendDebugMessage(message, severity)
        +showMessage(message, severity)
        +setDebugMode(mode)
        +bufferDebugMessage(message, severity)
    }
    class Communicator {
        <<object>>
        +client: LanguageClient
    }
    class LanguageClient {
        <<interface>>
        lsp4j proxy of the editor
    }
    class SerializedNames {
        +create(gson, type) TypeAdapter
    }
    class TypeAdapterFactory {
        <<interface>>
    }

    CommunicatorInterface <|.. Communicator
    Communicator --> LanguageClient : publishDiagnostics, showMessage, logMessage
    TypeAdapterFactory <|.. SerializedNames
    Server ..> Communicator : setRemoteProxy sets client
    DocumentChecks ..> CommunicatorInterface
```

# Package b.language.server.project

A B model: the file the user works on, its text in the editor, and the files it depends on.

```mermaid
classDiagram
    direction TB
    class Project {
        <<interface>>
        +mainFile: File
        +text: String
        +match(classicB, eventB) R
        +declaration(name, kind) Location
        +fileNamed(path) File
        +of(file, text)$ Project
    }
    class ClassicBProject {
        +operation(name) Location
    }
    class EventBProject {
        +loadMain() Loading
        +component(name) Component
        +resolve(...)
    }
    class Component {
        +file: File
        +unit: PParseUnit
        +kind: ComponentKind
        +name
    }
    class ComponentKind {
        <<enumeration>>
        MACHINE
        CONTEXT
    }
    class Loading {
        <<sealed>>
    }
    class Loaded
    class Failed
    class OpenDocuments {
        +update(file, text)
        +textOf(file) String
        +projectOf(file) Project
    }
    class Location {
        +file: File
        +range: SourceRange
    }
    class SourceRange {
        +start: SourcePosition
        +end: SourcePosition
    }
    class SourcePosition {
        +line: Int
        +column: Int
    }
    class DeclarationKind {
        <<enumeration>>
        CONSTANT
        VARIABLE
        INVARIANT
    }

    Project <|.. ClassicBProject
    Project <|.. EventBProject
    EventBProject *-- Component
    Component --> ComponentKind
    EventBProject ..> Loading
    Loading <|-- Loaded
    Loading <|-- Failed
    OpenDocuments ..> Project : Project.of with the editor text
    Project ..> Location
    Project ..> DeclarationKind
    Location *-- SourceRange
    SourceRange *-- SourcePosition
```

# Package b.language.server.analysis

What the server asks of the analysis of one kind of project, and the dispatcher that picks the analysis by the
project's kind.

```mermaid
classDiagram
    direction LR
    class Checker~P~ {
        <<interface>>
        +check(project, settings) Either of AnalysisFailure or problems
    }
    class HoverProvider~P~ {
        <<interface>>
        +hover(project, at) Markdown
    }
    class CompletionProvider~P~ {
        <<interface>>
        +complete(project, at) List~CompletionCandidate~
    }
    class ModelChecker~P~ {
        <<interface>>
        +modelCheck(project, settings) ModelCheckReport
    }
    class ProofObligationProvider~P~ {
        <<interface>>
        +proofObligations(project) List~ProofObligation~
    }
    class AnimationProvider~P~ {
        <<interface>>
        +animationModel(project) AnimationModel
    }
    class Analysis~P~ {
        <<interface>>
    }
    class AnalysisDispatcher {
        +close()
    }
    class ClassicBAnalysis
    class EventBAnalysis
    class ProBSession {
        one ProB process
    }

    Checker <|-- Analysis
    HoverProvider <|-- Analysis
    CompletionProvider <|-- Analysis
    ModelChecker <|-- Analysis
    ProofObligationProvider <|-- Analysis
    AnimationProvider <|-- Analysis
    Analysis <|.. AnalysisDispatcher : P = Project
    Analysis <|.. ClassicBAnalysis : P = ClassicBProject
    Analysis <|.. EventBAnalysis : P = EventBProject
    AnalysisDispatcher *-- ClassicBAnalysis : project.match
    AnalysisDispatcher *-- EventBAnalysis : project.match
    AnalysisDispatcher o-- ProBSession
    ClassicBAnalysis --> ProBSession : load, check, model check
    EventBAnalysis --> ProBSession : discharge proof obligations, model check
```

# Package b.language.server.analysis.classicB

The analysis of classical B, which ProB does.

# Package b.language.server.analysis.eventB

The analysis of Event-B in Camille notation: Rodin's static checker, and proof obligations discharged by ProB.

# Package b.language.server.analysis.documentation

The documentation of keywords and operators shown in hovers and completions, from the Rodin Handbook and ProB's
Summary of B Syntax.

# Package b.language.server.analysis.prob

The connection to ProB: one process per session, its preferences, and the problems it reports.

# Package b.language.server.analysis.prob.animation

The animation of a classical B or Event-B machine, in a ProB process of its own so that checking files while
animating does not disturb it.

## Lifecycle of an animation

```mermaid
stateDiagram-v2
    direction TB
    [*] --> Idle
    Idle --> Loading : start(model)
    Loading --> Idle : AnimationLoad.Failed / Failure
    Loading --> Running : AnimationLoad.Loaded / AnimationView
    Running --> Loading : restart
    Running --> Idle : stop / Stopped
    Idle --> [*] : close, ends the ProB process
    Running --> [*] : close, ends the ProB process

    Idle : Idle
    Idle : the ProB process starts with the first start
    Loading : Loading
    Loading : the AnimationModel is loaded into a new state space
    Running : Running
    Running : perform(action) / AnimationView
    Running : visualize(request) / Page or Table
    Running : actions - execute, executeWith, back, forward,
    Running : goto, random, find, modelCheck
```

## The client and the animation, back and forth

```mermaid
graph TD
    subgraph client["Client"]
        user(["User"])
        action["source action ProB: animate<br>prob.animate.open, handled by the client"]
        panel["animation panel"]
    end

    subgraph server["Server"]
        commands["BWorkspaceService.executeCommand<br>Commands decodes the request"]
        model["AnalysisDispatcher.animationModel(project)"]
        animation["Animation<br>start, perform, visualize, stop"]
        response["AnimationView: state, history, events, invariants<br>or Page / Table, Stopped, Failure"]
    end

    prob[("ProB process<br>of the animation")]

    user -->|"1. chooses"| action
    action -->|"2. opens"| panel
    panel -->|"3. prob.animate {action: start, uri}"| commands
    commands -->|"4. start"| model
    model -->|"5. AnimationModel"| animation
    panel -->|"prob.animate {action, argument, count}<br>prob.visualize {kind, command, formulas}"| commands
    commands -->|"execute, back, forward, goto, random,<br>find, modelCheck, restart, visualize, stop"| animation
    animation <-->|"load, step the trace, render"| prob
    animation --> response
    response -->|"JSON result"| panel
    panel -->|"shows the new state"| user
    user -->|"clicks an event or a step"| panel
```
