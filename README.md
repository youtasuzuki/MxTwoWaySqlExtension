# Description
- This is an add-on for Mendix Studio Pro (v10.24 and v11.12 or later) designed for development using the TwoWaySQL, SaferOQL, MxDBUnit and jXLSer modules. It allows you to open SQL/OQL/XLSX files in an external editor directly from the Microflow editor in Studio Pro with a single action (Ctrl+Shift+K).  
-- Up to versions v2.2.0 and v1.1024.2, the shortcut was Ctrl+Shift+L; however, as this conflicted with a built-in feature of Studio-Pro, it has been changed to Ctrl+Shift+K starting with versions v2.3.0 and v1.1024.3.--
- The add-on automatically launches the application associated with the ".sql", ".oql" and ".xlsx" file extensions on your Windows system and opens the corresponding file.
# Usage
- If there is only one TwoWaySQL or SaferOQL or MxDBUnit or jXLSer Java action in the Microflow you are currently viewing, you can open the associated file by simply pressing Ctrl+Shift+K.
- If there are multiple TwoWaySQL or SaferOQL or MxDBUnit or jXLSer Java actions in the Microflow, you will need to select the specific one you wish to open.
- From versions v2.3.0 and v1.1024.3, a feature has been added to verify the consistency of TwoWaySQL bind variables between Microflows and SQL files. This can be executed using Ctrl+Shift+J.
