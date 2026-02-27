import urllib.request
import json
import zipfile
import io
import os

token = "" # Optional, public repo 
url = "https://api.github.com/repos/ravisah17/findmyphone/actions/runs?per_page=1"
req = urllib.request.Request(url)
with urllib.request.urlopen(req) as response:
    data = json.loads(response.read().decode())
    run_id = data['workflow_runs'][0]['id']
    
log_url = f"https://api.github.com/repos/ravisah17/findmyphone/actions/runs/{run_id}/logs"
try:
    req = urllib.request.Request(log_url)
    with urllib.request.urlopen(req) as response:
        with zipfile.ZipFile(io.BytesIO(response.read())) as z:
            for filename in z.namelist():
                if "Build APK with Gradle" in filename:
                    with z.open(filename) as f:
                        lines = f.read().decode('utf-8').split('\n')
                        for line in lines:
                            if "e: " in line or "FAILURE" in line or "Task :app:processDebugMainManifest FAILED" in line or "error:" in line.lower():
                                print(line.strip())
except Exception as e:
    print(f"Error: {e}")
