"""Reproduce the owner's exact seven-block layout; saved locally and hash-recorded."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from scene import render
render(1)
