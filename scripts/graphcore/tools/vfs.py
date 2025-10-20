#      The Certora Prover
#      Copyright (C) 2025  Certora Ltd.
#
#      This program is free software: you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation, version 3 of the License.
#
#      This program is distributed in the hope that it will be useful,
#      but WITHOUT ANY WARRANTY; without even the implied warranty of
#      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
#      GNU General Public License for more details.
#
#      You should have received a copy of the GNU General Public License
#      along with this program.  If not, see <https://www.gnu.org/licenses/>.

from typing_extensions import TypedDict
from typing import NotRequired, TypeVar, Any, Annotated, Type, Callable, Sequence, ContextManager, Protocol, Iterator, Generic
import re
from functools import cache
import pathlib
import contextlib
import tempfile

from pydantic import BaseModel, Field, create_model

from langchain_core.tools import tool, InjectedToolCallId
from langchain_core.tools.base import BaseTool
from langgraph.prebuilt import InjectedState
from langgraph.graph import MessagesState
from langgraph.types import Command


from graphcore.graph import tool_output

def merge_vfs(left: dict[str, str], right: dict[str, str]) -> dict[str, str]:
    new_left = left.copy()
    for (f_name, cont) in right.items():
        new_left[f_name] = cont
    return new_left


class VFSState(MessagesState):
    vfs: Annotated[dict[str, str], merge_vfs]

InputType = TypeVar("InputType", bound=VFSState)

StateVar = TypeVar("StateVar", contravariant=True)

class TempDirectoryProvider(Protocol):
    def __call__(self) -> ContextManager[str]:
        ...

class VFSAccessor(Protocol[StateVar]):
    def materialize(self, state: StateVar, debug: bool = False) -> ContextManager[str]:
        ...

    def iterate(self, state: StateVar) -> Iterator[tuple[str, bytes]]:
        ...

    def get(self, state: StateVar, file: str) -> bytes | None:
        ...


@contextlib.contextmanager
def debugging_tmp_directory() -> Iterator[str]:
    temp_dir = tempfile.mkdtemp()
    print(f"DEBUG: Working directory: {temp_dir}")
    yield temp_dir

@contextlib.contextmanager
def _materialize(
    provider: TempDirectoryProvider,
    fs_layer: str | None,
    state: VFSState
) -> Iterator[str]:
    with provider() as dir:
        files = state["vfs"]
        target = pathlib.Path(dir)
        for (k, v) in files.items():
            tgt = target / k
            tgt.parent.mkdir(exist_ok=True, parents=True)
            tgt.write_text(v)

        if fs_layer is not None:
            mounted_path = pathlib.Path(fs_layer)
            for p in mounted_path.rglob("*"):
                if not p.is_file():
                    continue
                rel_path = p.relative_to(mounted_path)
                copy_path = target / rel_path
                if copy_path.exists():
                    continue
                copy_path.parent.mkdir(exist_ok=True, parents=True)
                copy_path.write_text(p.read_text())
        yield dir


class VFSToolConfig(TypedDict):
    immutable: bool
    fs_layer: NotRequired[str | None]
    forbidden_read: NotRequired[str]
    forbidden_write: NotRequired[str]

    put_doc_extra: NotRequired[str]
    get_doc_extra: NotRequired[str]


class _VFSAccess(Generic[InputType]):
    def __init__(self, conf: VFSToolConfig):
        self.conf = conf

    def materialize(self, state: InputType, debug: bool = False) -> ContextManager[str]:
        manager : TempDirectoryProvider = tempfile.TemporaryDirectory
        if debug:
            manager = debugging_tmp_directory
        return _materialize(manager, self.conf.get("fs_layer"), state)

    def get(self, state: InputType, file: str) -> bytes | None:
        if file in state["vfs"]:
            return state["vfs"][file].encode("utf-8")
        fs_layer = self.conf.get("fs_layer", None)
        if fs_layer is None:
            return None
        path = pathlib.Path(fs_layer) / file
        if path.is_file():
            return path.read_bytes()
        else:
            return None

    def iterate(self, state: InputType) -> Iterator[tuple[str, bytes]]:
        d = state["vfs"]
        for (p, v) in d.items():
            yield (p, v.encode("utf-8"))

        if (fs_layer := self.conf.get("fs_layer", None)) is not None:
            root = pathlib.Path(fs_layer)
            for child in root.rglob("*"):
                if not child.is_file():
                    continue
                rel_path = child.relative_to(root)
                if str(rel_path) in d:
                    continue
                yield (str(rel_path), child.read_bytes())


def vfs_tools(conf: VFSToolConfig, ty: Type[InputType]) -> tuple[list[BaseTool], VFSAccessor[InputType]]:
    def inject(doc_extra: str | None = None) -> Callable[[type[BaseModel]], type[BaseModel]]:
        def to_ret(s: type[BaseModel]) -> type[BaseModel]:
            f: dict[str, Any] = {k: (v.annotation, v) for (k, v) in s.model_fields.items()}
            f["state"] = Annotated[ty, InjectedState]
            d_string = s.__doc__
            if doc_extra is not None:
                d_string = f"{d_string}\n\n{doc_extra}"
            return create_model(
                s.__name__,
                __doc__ = d_string,
                **f
            )
        return to_ret

    class PutFileSchema(BaseModel):
        tool_call_id: Annotated[str, InjectedToolCallId]

        files: dict[str, str] = \
            Field(description="A dictionary associating RELATIVE pathnames to the contents to store at those path names. Do NOT include a leading `./` it is always implied. "
                  "The provided contents for the file are durably stored into the virtual filesystem. "
                  "Any files contents with the same path named stored in previous tool calls are overwritten.")

    pf_doc = "Put file contents onto the virtual file system used by this workflow."
    if "put_doc_extra" in conf:
        pf_doc += f"\n\n{conf['put_doc_extra']}"

    PutFileSchema.__doc__ = pf_doc

    # returns true if the file is okay to put or get
    def make_checker(patt: str | None) -> Callable[[str], bool]:
        if patt is None:
            return lambda f_name: True
        match = re.compile(patt)
        return lambda f_name: match.fullmatch(f_name) is None

    put_filter = make_checker(conf.get("forbidden_write"))
    get_filter = make_checker(conf.get("forbidden_read"))

    @tool(args_schema=PutFileSchema)
    def put_file(
        files: dict[str, str],
        tool_call_id: Annotated[str, InjectedToolCallId],
    ) -> str | Command:
        for (k, _) in files.items():
            if not put_filter(k):
                return f"Illegal put operation: cannot write {k} on the VFS"
        return tool_output(
            tool_call_id=tool_call_id,
            res={
                "vfs": files
            }
        )

    @inject(doc_extra=conf.get('get_doc_extra'))
    class GetFileSchema(BaseModel):
        """
        Read the contents of the VFS at some relative path.

        If the path doesn't exist, this function returns "File not found"
        """
        path: str = Field(description="The relative path of the file on the VFS. IMPORTANT: Do NOT include a leading `./` it is implied")

    @tool(args_schema=GetFileSchema)
    def get_file(
        path: str,
        state: Annotated[InputType, InjectedState]
    ) -> str:
        if not get_filter(path):
            return "File not found"

        if path not in state["vfs"]:
            if conf.get("fs_layer", None) is not None:
                layer = conf.get("fs_layer")
                assert layer is not None
                p = pathlib.Path(layer)
                child = p / path
                if child.is_file():
                    return child.read_text()
            return "File not found"
        else:
            return state["vfs"][path]

    @cache
    def list_underlying() -> Sequence[str]:
        layer = conf.get("fs_layer", None)
        if layer is None:
            return []
        base = pathlib.Path(layer)
        return [str(f.relative_to(base)) for f in base.rglob("*") if f.is_file()]

    @inject()
    class ListFileSchema(BaseModel):
        """
        Lists all file contents of the VFS, including in any subdirectories. Directory entries are *not* included.
        Each file in the VFS has its own line in the output, any empty lines should be ignored.
        """
        pass

    @tool(args_schema=ListFileSchema)
    def list_files(
        state: Annotated[InputType, InjectedState]
    ) -> str:
        to_ret = []
        for (k, _) in state["vfs"].items():
            if not get_filter(k):
                continue
            to_ret.append(k)
        for f_name in list_underlying():
            if not get_filter(f_name) or f_name in state["vfs"]:
                continue
            to_ret.append(f_name)
        return "\n".join(to_ret)

    @inject()
    class GrepFileSchema(BaseModel):
        """
        Search for a specific string in the files on the VFS. Returns a list of
        file names which contain the query somewhere in their contents. Matching
        file names are output one per line. Empty lines should be ignored.
        """

        search_string: str = Field(description="The query string to search for provided as a python regex. Thus, you must escape any special characters (like [, |, etc.)")

    @tool(args_schema=GrepFileSchema)
    def grep_files(
        state: Annotated[InputType, InjectedState],
        search_string: str
    ) -> str:
        comp: re.Pattern
        try:
            comp = re.compile(search_string)
        except Exception:
            return "Illegal pattern name, check your syntax and try again."

        matches: list[str] = []

        for (k, v) in state["vfs"].items():
            if not get_filter(k):
                continue
            if comp.search(v) is not None:
                matches.append(k)

        if (layer := conf.get("fs_layer", None)) is not None:
            p = pathlib.Path(layer)
            for f in p.rglob("*"):
                if not f.is_file():
                    continue
                rel_name = str(f.relative_to(p))
                if not get_filter(rel_name):
                    continue
                if not comp.search(f.read_text()):
                    continue
                matches.append(rel_name)

        return "\n".join(matches)

    tools: list[BaseTool] = [get_file, list_files, grep_files]
    if not conf["immutable"]:
        tools.append(put_file)

    materializer = _VFSAccess[InputType](conf=conf)

    return (tools, materializer)
