using System;
using System.Collections;
using System.Collections.Generic;
using System.ComponentModel;
using System.ComponentModel.Composition;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using Mendix.StudioPro.ExtensionsAPI.UI.Menu;
using Mendix.StudioPro.ExtensionsAPI.UI.Services;

namespace TwoWaySqlExtension;

[Export(typeof(MenuExtension))]
public class OpenSqlFileMenuExtension : MenuExtension, IDisposable
{
    private readonly IDockingWindowService _dockingWindowService;
    private Thread? _hotKeyThread;
    private volatile bool _isRunning = true;

    // パラメータ名（小文字で定義）と対象サブフォルダの定義
    private static readonly (string ParamKey, string SubDir)[] SupportedActions = new[]
    {
        ("twowaysqlfilename", Path.Combine("resources", "sql")),
        ("saferoqlfilename", Path.Combine("resources", "saferoql"))
    };

    // Win32 API 定義
    [DllImport("user32.dll", EntryPoint = "MessageBoxW", CharSet = CharSet.Unicode)]
    private static extern int Win32MessageBox(IntPtr hWnd, string text, string caption, uint type);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

    [DllImport("user32.dll")]
    private static extern int GetMessage(out MSG lpMsg, IntPtr hWnd, uint wMsgFilterMin, uint wMsgFilterMax);

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage([In] ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage([In] ref MSG lpMsg);

    [DllImport("user32.dll")]
    private static extern bool PostThreadMessage(uint idThread, uint Msg, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MSG
    {
        public IntPtr hwnd;
        public uint message;
        public IntPtr wParam;
        public IntPtr lParam;
        public uint time;
        public POINT pt;
    }

    private const uint MB_OK = 0x00000000;
    private const uint MB_ICONERROR = 0x00000010;
    private const uint MB_ICONWARNING = 0x00000030;
    private const uint MB_ICONINFORMATION = 0x00000040;

    private const int WM_HOTKEY = 0x0312;
    private const uint WM_QUIT = 0x0012;
    private const uint MOD_CONTROL = 0x0002;
    private const uint MOD_SHIFT = 0x0004;
    private const uint VK_L = 0x4C; // 'L' キー
    private const int HOTKEY_ID = 9182;
    private uint _hotKeyThreadId;

    [ImportingConstructor]
    public OpenSqlFileMenuExtension(IDockingWindowService dockingWindowService)
    {
        _dockingWindowService = dockingWindowService;
        StartHotKeyListener();
    }

    private void StartHotKeyListener()
    {
        _hotKeyThread = new Thread(() =>
        {
            _hotKeyThreadId = GetCurrentThreadId();
            if (!RegisterHotKey(IntPtr.Zero, HOTKEY_ID, MOD_CONTROL | MOD_SHIFT, VK_L))
            {
                return;
            }

            while (_isRunning && GetMessage(out MSG msg, IntPtr.Zero, 0, 0) > 0)
            {
                if (msg.message == WM_HOTKEY && msg.wParam.ToInt32() == HOTKEY_ID)
                {
                    Execute();
                }
                TranslateMessage(ref msg);
                DispatchMessage(ref msg);
            }

            UnregisterHotKey(IntPtr.Zero, HOTKEY_ID);
        })
        {
            IsBackground = true
        };
        _hotKeyThread.Start();
    }

    public override IEnumerable<MenuViewModel> GetMenus()
    {
        yield return new MenuViewModel(
            "Open SQL/OQL File in External Editor (Ctrl+Shift+L)",
            Execute
        );
    }

    private void Execute()
    {
        try
        {
            var dockType = _dockingWindowService.GetType();

            // 1. プロジェクトルートの取得
            var projectManagerField = dockType.GetField("<projectManager>P", BindingFlags.NonPublic | BindingFlags.Instance);
            var projectManager = projectManagerField?.GetValue(_dockingWindowService);
            var project = projectManager?.GetType().GetProperty("Project")?.GetValue(projectManager);
            var projectDir = project?.GetType().GetProperty("ProjectDir")?.GetValue(project)?.ToString();

            if (string.IsNullOrEmpty(projectDir))
            {
                ShowDialog("Failed to retrieve the project directory.", MB_ICONERROR);
                return;
            }

            // 2. アクティブなエディタ・タブの取得
            var docEditorManagerField = dockType.GetField("<documentEditorManager>P", BindingFlags.NonPublic | BindingFlags.Instance);
            var docEditorManager = docEditorManagerField?.GetValue(_dockingWindowService);

            if (docEditorManager == null)
            {
                ShowDialog("No document editor manager found.", MB_ICONWARNING);
                return;
            }

            var activeTabField = docEditorManager.GetType().GetField("activeTab", BindingFlags.NonPublic | BindingFlags.Instance);
            var activeTab = activeTabField?.GetValue(docEditorManager);

            var getActiveDocMethod = docEditorManager.GetType().GetMethod("GetActiveDocument", BindingFlags.Public | BindingFlags.Instance);
            var activeDoc = getActiveDocMethod?.Invoke(docEditorManager, null);

            if (activeDoc == null)
            {
                ShowDialog("No active editor found. Please open a Microflow tab.", MB_ICONWARNING);
                return;
            }

            // 3. 選択要素の判定 (Editor -> Selection / SelectedObject)
            var selectedObjects = GetSelectedObjectsFromActiveTab(activeTab);

            (string RawPath, string SubDir)? targetInfo = null;
            if (selectedObjects.Count > 0)
            {
                foreach (var sel in selectedObjects)
                {
                    targetInfo = ExtractFileInfoFromObject(sel);
                    if (targetInfo != null) break;
                }
            }

            // 4. 選択要素に対象アクションがない場合、Microflow 全体を走査
            if (targetInfo == null)
            {
                var allMatches = FindAllMatchingActionsInMicroflow(activeDoc);

                if (allMatches.Count == 0)
                {
                    ShowDialog("No TwoWaySQL or SaferOQL action found in the current Microflow.", MB_ICONINFORMATION);
                    return;
                }

                if (allMatches.Count > 1)
                {
                    // 2個以上あり、かつ未選択の場合は誤操作防止のため警告
                    ShowDialog($"Multiple ({allMatches.Count}) query actions found in this Microflow.\nPlease select the specific action on the canvas to open.", MB_ICONWARNING);
                    return;
                }

                // ちょうど1個だけの場合は自動採用
                targetInfo = allMatches[0];
            }

            // 5. パスの正規化
            var cleanRelativePath = targetInfo.Value.RawPath.Trim('\'', '"', ' ', '\r', '\n')
                                                           .Replace('/', Path.DirectorySeparatorChar)
                                                           .Replace('\\', Path.DirectorySeparatorChar);

            // 6. resources/ 配下の絶対パス組み立て
            var fullPath = Path.Combine(projectDir, targetInfo.Value.SubDir, cleanRelativePath);

            var targetDir = Path.GetDirectoryName(fullPath);
            if (!string.IsNullOrEmpty(targetDir) && !Directory.Exists(targetDir))
            {
                Directory.CreateDirectory(targetDir);
            }

            if (!File.Exists(fullPath))
            {
                File.WriteAllText(fullPath, $"-- Query File: {cleanRelativePath}\n");
            }

            // 7. 規定アプリまたはプログラムから開くダイアログ
            OpenFileWithDefaultApp(fullPath);
        }
        catch (Exception ex)
        {
            ShowDialog($"An error occurred:\n{ex.Message}", MB_ICONERROR);
        }
    }

    private static List<object> GetSelectedObjectsFromActiveTab(object? activeTab)
    {
        var result = new List<object>();
        if (activeTab == null) return result;

        var editorProp = activeTab.GetType().GetProperty("Editor", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        var editor = editorProp?.GetValue(activeTab);
        if (editor == null) return result;

        var editorType = editor.GetType();

        // 1. Selection プロパティを確認 (Mendix.Modeler.Common.Selection)
        var selectionProp = editorType.GetProperty("Selection", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        var selection = selectionProp?.GetValue(editor);
        if (selection != null)
        {
            if (selection is IEnumerable selEnum && !(selection is string))
            {
                foreach (var item in selEnum)
                {
                    if (item != null) result.Add(item);
                }
            }
            else
            {
                foreach (var p in selection.GetType().GetProperties(BindingFlags.Public | BindingFlags.Instance))
                {
                    if (p.GetIndexParameters().Length > 0) continue;
                    try
                    {
                        var val = p.GetValue(selection);
                        if (val is IEnumerable list && !(val is string))
                        {
                            foreach (var item in list)
                            {
                                if (item != null) result.Add(item);
                            }
                        }
                    }
                    catch { }
                }
            }
        }

        // 2. SelectedObject プロパティを確認
        var selectedObjProp = editorType.GetProperty("SelectedObject", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance);
        var selectedObj = selectedObjProp?.GetValue(editor);
        if (selectedObj != null && !result.Contains(selectedObj))
        {
            result.Add(selectedObj);
        }

        return result;
    }

    private static List<(string RawPath, string SubDir)> FindAllMatchingActionsInMicroflow(object microflow)
    {
        var matches = new List<(string RawPath, string SubDir)>();

        var objCollectionProp = microflow.GetType().GetProperty("ObjectCollection", BindingFlags.Public | BindingFlags.Instance);
        var objCollection = objCollectionProp?.GetValue(microflow);
        if (objCollection == null) return matches;

        var objectsProp = objCollection.GetType().GetProperty("Objects", BindingFlags.Public | BindingFlags.Instance);
        var objects = objectsProp?.GetValue(objCollection) as IEnumerable;
        if (objects == null) return matches;

        foreach (var mfObj in objects)
        {
            if (mfObj == null) continue;
            var info = ExtractFileInfoFromObject(mfObj);
            if (info != null)
            {
                matches.Add(info.Value);
            }
        }

        return matches;
    }

    private static (string RawPath, string SubDir)? ExtractFileInfoFromObject(object targetObj)
    {
        if (targetObj == null) return null;

        var actionProp = targetObj.GetType().GetProperty("Action", BindingFlags.Public | BindingFlags.Instance);
        var action = actionProp?.GetValue(targetObj) ?? targetObj;

        var mappingsProp = action.GetType().GetProperty("ParameterMappings", BindingFlags.Public | BindingFlags.Instance);
        var mappings = mappingsProp?.GetValue(action) as IEnumerable;
        if (mappings == null) return null;

        foreach (var mapping in mappings)
        {
            if (mapping == null) continue;

            var paramName = mapping.GetType().GetProperty("Parameter")?.GetValue(mapping)?.ToString() ?? "";
            var paramId = mapping.GetType().GetProperty("ParameterId")?.GetValue(mapping)?.ToString() ?? "";

            foreach (var (targetParamKey, subDir) in SupportedActions)
            {
                bool isMatched = paramName.IndexOf(targetParamKey, StringComparison.OrdinalIgnoreCase) >= 0 ||
                                 paramId.IndexOf(targetParamKey, StringComparison.OrdinalIgnoreCase) >= 0;

                if (isMatched)
                {
                    var valObj = mapping.GetType().GetProperty("Value", BindingFlags.Public | BindingFlags.Instance)?.GetValue(mapping);
                    if (valObj == null) continue;

                    var argObj = valObj.GetType().GetProperty("Argument", BindingFlags.Public | BindingFlags.Instance)?.GetValue(valObj);
                    if (argObj != null)
                    {
                        var exprText = argObj.GetType().GetProperty("Expr", BindingFlags.Public | BindingFlags.Instance)?.GetValue(argObj)?.ToString()
                                    ?? argObj.ToString();

                        if (!string.IsNullOrEmpty(exprText))
                        {
                            return (exprText, subDir);
                        }
                    }
                }
            }
        }

        return null;
    }

    private static void OpenFileWithDefaultApp(string filePath)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = filePath,
                UseShellExecute = true
            };
            Process.Start(psi);
        }
        catch (Win32Exception)
        {
            var psi = new ProcessStartInfo
            {
                FileName = "rundll32.exe",
                Arguments = $"shell32.dll,OpenAs_RunDLL \"{filePath}\"",
                UseShellExecute = true
            };
            Process.Start(psi);
        }
    }

    private static void ShowDialog(string message, uint iconType)
    {
        Win32MessageBox(IntPtr.Zero, message, "TwoWaySQL / SaferOQL Extension", MB_OK | iconType);
    }

    public void Dispose()
    {
        _isRunning = false;
        if (_hotKeyThreadId != 0)
        {
            PostThreadMessage(_hotKeyThreadId, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
        }
    }
}