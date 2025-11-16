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

from typing import Literal, Optional, override, Protocol, Any, Self, TypeVar, Generic, Iterator
from typing_extensions import Iterable
from abc import ABC, abstractmethod
import shutil
import sqlite3
from dataclasses import dataclass
import pathlib
from contextlib import contextmanager
import re

from psycopg import Connection

from langchain_core.tools import BaseTool, tool

from pydantic import BaseModel, Field

class UnifiedMemorySchema(BaseModel):
    """
    unified tool schema. Not actually sent to the LLM, but necessary
    to adapt the rigid input schemas of langgraph tools
    to the "dependent" schema used by the anthropic memory tool.

    This comment is just here to make the tool annotation happy.
    """
    command: Literal["view", "create", "str_replace", "insert", "delete", "rename"] = Field(
        description="Memory command: create, update, delete, search, etc."
    )
    path: Optional[str] = Field(default=None, description="path to view/create/str_replace/delete")
    view_range: Optional[list[int]] = Field(default=None, description="range for view")
    # Include all possible fields across commands
    file_text: Optional[str] = Field(default=None, description="Content for create")

    old_str: Optional[str] = Field(description="old string for str_replace", default=None)
    new_str: Optional[str] = Field(description="new string for str_replace", default=None)

    insert_line: Optional[int] = Field(description="Line number at which to insert", default=None)
    insert_text: Optional[str] = Field(description="Text to insert", default=None)

    old_path: Optional[str] = Field(description="original path", default=None)
    new_path: Optional[str] = Field(description="new path", default=None)

class MemoryBackendError(RuntimeError):
    """
    Special exception caught by the memory tool and transformed into an error message
    for the LLM.
    """
    def __init__(self, msg: str):
        super().__init__(msg)

class MemoryBackend(ABC):
    """
    Implements most of the operations required by the memory tool, but leaves the details of
    - file stat
    - directory listing
    - creating/reading/deleting files/folders
    - renaming

    To the concrete backends. Most of implementations here were actually adapted from Anthropic's reference
    code.
    """
    @dataclass
    class FileStat:
        exists: bool
        is_dir: bool

    @abstractmethod
    def read_file(self, path: str) -> Optional[str]:
        ...

    @abstractmethod
    def write_file(self, path: str, content: str):
        ...

    @abstractmethod
    def stat(self, path: str) -> FileStat:
        ...

    @abstractmethod
    def list_dir(self, path: str) -> Iterable[tuple[str, bool]]:
        ...

    @abstractmethod
    def rm(self, path: str) -> str:
        ...

    @abstractmethod
    def do_rename(self, old_path: str, new_path: str) -> str:
        ...

    def _validate(self, path: str) -> str:
        r = pathlib.Path(path).resolve()
        if not r.is_relative_to("/memories"):
            raise MemoryBackendError(f"{str(r)} is not rooted in /memories")

        return str(r)

    def view(self, path: str, view_range: Optional[tuple[int, int]]) -> str:
        path = self._validate(path)
        s = self.stat(path)
        if not s.exists:
            return f"ERROR: Path not found: {path}"

        if s.is_dir:
            to_ret = []
            for (p, is_dir) in self.list_dir(path):
                child_path = p
                if is_dir:
                    child_path += "/"
                to_ret.append(f"- {child_path}")
            return "\n".join(to_ret)

        else:
            content = self.read_file(path)
            assert content is not None
            lines = content.splitlines()
            if view_range:
                start_line = max(1, view_range[0]) - 1
                end_line = len(lines) if view_range[1] == -1 else view_range[1]
                lines = lines[start_line:end_line]
                start_num = start_line + 1
            else:
                start_num = 1
            numbered_lines = [f"{i + start_num:4d}: {line}" for i, line in enumerate(lines)]
            return "\n".join(numbered_lines)

    def create(self, path: str, content: str) -> str:
        curr_path = self._validate(path)
        stat = self.stat(curr_path)
        if stat.exists and stat.is_dir:
            return f"Error: {path} exists and is a directory"
        self.write_file(self._validate(path), content)
        return f"File created successfully at {path}"

    def delete(self, path: str) -> str:
        path = self._validate(path)
        if path == "/memories":
            return "Error: cannot delete the /memories directory"

        return self.rm(path)

    def rename(self, old_path: str, new_path: str) -> str:
        old_path = self._validate(old_path)
        new_path = self._validate(new_path)

        old_stat = self.stat(old_path)
        new_stat = self.stat(new_path)
        if not old_stat.exists:
            return (f"ERROR: Source path not found: {old_path}")
        if new_stat.exists:
            return (f"ERROR: Destination already exists: {new_path}")

        return self.do_rename(old_path, new_path)

    def insert(self, path: str, insert_line: int, insert_text: str) -> str:
        path = self._validate(path)
        stat = self.stat(path)

        if not stat.exists or stat.is_dir:
            return (f"ERROR: File not found: {path}")
        contents = self.read_file(path)
        assert contents is not None
        lines = contents.splitlines()

        if insert_line < 0 or insert_line > len(lines):
            return (f"ERROR: Invalid insert_line {insert_line}. Must be 0-{len(lines)}")

        lines.insert(insert_line, insert_text.rstrip("\n"))

        to_write = "\n".join(lines) + "\n"
        self.write_file(path, to_write)

        return f"Text inserted at line {insert_line} in {path}"

    def str_replace(self, path: str, old_str: str, new_str: str) -> str:
        path = self._validate(path)
        full_path = self.stat(path)

        if not full_path.exists or full_path.is_dir:
            return f"File not found: {path}"

        content = self.read_file(path)
        assert content is not None

        count = content.count(old_str)
        if count == 0:
            return (f"ERROR: Text not found in {path}")
        elif count > 1:
            return (f"ERROR: Text appears {count} times in {path}. Must be unique.")

        new_content = content.replace(old_str, new_str)
        self.write_file(path, new_content)
        return f"File {path} has been edited"

class DBCursor(Protocol):
    """
    Intersection type necessary for the SQLBackend, implemented by both the
    postgres and sqlite cursor classes.
    """
    def execute(self, query: str, vars: tuple[Any, ...] | dict[str, Any], /) -> Any:
        ...

    def fetchone(self, /) -> None | tuple[Any, ...]:
        ...

    def close(self) -> None:
        ...

    def __iter__(self) -> Self:
        ...

    def __next__(self) -> tuple[Any, ...]:
        ...

    @property
    def rowcount(self) -> int:
        ...


class DBConnection(Protocol):
    """
    Intersection type necessary for the SQLBackend, implemented by both the
    postgres and sqlite connection classes.
    """
    def __enter__(self) -> Self:
        ...

    def __exit__(
        self,
        exc_type: Optional[type[BaseException]],
        exc_val: Optional[BaseException],
        exc_tb: Optional[Any],
        /
    ) -> Optional[bool]:
        ...

    def cursor(self, /) -> DBCursor:
        ...

CONN = TypeVar('CONN')

CURSOR = TypeVar('CURSOR', bound=DBCursor)

class SQLBackend(MemoryBackend, Generic[CONN]):
    """
    Generic SQL backend for the memory tool.
    """
    def __init__(self, ns: str, conn: CONN):
        self.conn = conn
        self.ns = ns
        self._setup()

    @property
    @abstractmethod
    def pos_placeholder(self) -> str:
        """
        sqlite's driver uses ? for placeholder, postgres uses %s. make this a property
        """
        ...

    @abstractmethod
    def named_placeholder(self, nm: str) -> str:
        """
        again, sqlite uses :name for named placeholders, postgres uses %(name)s. push
        this into the implementers.
        """
        ...

    @abstractmethod
    def _setup(self):
        """
        Create the table/indices necessary for the operation of this memory backend.
        """
        ...

    @contextmanager
    def _cursor(self) -> Iterator[DBCursor]:
        """
        postgres' cursor is a context manager, sqlite's isn't.
        Both use the connection as a context manager for transaction control.
        Paper over this difference, so that when `with self._cursor()` exits
        the transaction is committed (or rolled back) and the cursor is closed.
        """
        with self.conn:
            cur = self.conn.cursor()
            try:
                yield cur
            finally:
                cur.close()

    def _mkdirs(self, cursor: DBCursor, path: pathlib.Path) -> str | None:
        if str(path) == '/':
            return None
        parent = self._mkdirs(cursor, path.parent)
        cursor.execute(f"""
SELECT is_directory FROM memories_fs WHERE namespace = {self.pos_placeholder} AND full_path = {self.pos_placeholder}
                       """, (self.ns, str(path)))
        r = cursor.fetchone()
        if r is not None:
            if not r[0]:
                raise MemoryBackendError(f"Cannot create directory at {path}: path already exists and is a file")
            return str(path)
        curr_name = path.name
        cursor.execute(f"""
INSERT INTO memories_fs(
    namespace,
    entry_name,
    full_path,
    parent_path,
    is_directory
) VALUES ({self.pos_placeholder}, {self.pos_placeholder}, {self.pos_placeholder}, {self.pos_placeholder}, TRUE);
                       """, (self.ns, curr_name, str(path), parent))
        return str(path)

    @abstractmethod
    def do_replace_first(self, replace_attribute: str, to_replace: str, replace_with: str, seq: int) -> tuple[str, dict[str, str]]:
        """
        Generate sql syntax to replace the first occurence of to_replace with replace_with in the column replace_attribute.

        Return a tuple of the sql, and a dictionary binding the parameters (if any) used in that sql. seq is passed to help give fresh names
        to these parameters.
        """
        ...

    @override
    def do_rename(self, old_path: str, new_path: str) -> str:
        orig_is_dir = self.stat(old_path).is_dir
        new_path_obj = pathlib.Path(new_path)
        if not orig_is_dir:
            with self._cursor() as cur:
                new_parent = new_path_obj.parent
                self._mkdirs(cur, new_parent)
                cur.execute(f"""
UPDATE memories_fs SET entry_name = {self.pos_placeholder}, full_path = {self.pos_placeholder}, parent_path = {self.pos_placeholder} WHERE full_path = {self.pos_placeholder} AND namespace = {self.pos_placeholder}
                            """, (new_path_obj.name, new_path, new_parent, self.ns))
                return f"Renamed file {old_path} -> {new_path}"
        with self._cursor() as cur:
            self._mkdirs(cur, new_path_obj.parent)
            params = {
                "orig_path": old_path,
                "new_name": new_path_obj.name,
                "new_parent": str(new_path_obj.parent),
                "ns": self.ns,
                "path_like": old_path + "%"
            }
            (replace_full_path, replace_full_param) = self.do_replace_first(
                "m.full_path",
                old_path,
                str(new_path_obj),
                0
            )
            (replace_parent_path, replace_parent_param) = self.do_replace_first(
                "m.parent_path",
                old_path,
                str(new_path_obj),
                1
            )
            params.update(replace_parent_param)
            params.update(replace_full_param)

            cur.execute(f"""
UPDATE memories_fs AS m SET
entry_name = CASE m.full_path
WHEN {self.named_placeholder("orig_path")} THEN {self.named_placeholder("new_name")}
ELSE m.entry_name
END,
full_path = {replace_full_path},
parent_path = CASE m.full_path
WHEN {self.named_placeholder("orig_path")} THEN {self.named_placeholder("new_parent")}
ELSE {replace_parent_path}
END WHERE namespace = {self.named_placeholder("ns")} AND full_path LIKE {self.named_placeholder("path_like")}
                        """, params)
        return f"Renamed directory {old_path} -> {new_path}"

    @override
    def list_dir(self, path: str) -> Iterable[tuple[str, bool]]:
        with self._cursor() as cur:
            cur.execute(f"SELECT entry_name, is_directory FROM memories_fs WHERE namespace = {self.pos_placeholder} AND parent_path = {self.pos_placeholder}", (self.ns, path))
            for r in cur:
                yield (r[0], r[1])

    @override
    def rm(self, path: str) -> str:
        with self._cursor() as cur:
            cur.execute(f"DELETE FROM memories_fs WHERE namespace = {self.pos_placeholder} AND full_path = {self.pos_placeholder}", (self.ns, path))
            return f"Deleted {cur.rowcount} entries"

    @override
    def read_file(self, path: str) -> Optional[str]:
        with self._cursor() as cur:
            cur.execute(f"SELECT contents FROM memories_fs WHERE namespace = {self.pos_placeholder} AND full_path = {self.pos_placeholder} AND contents IS NOT NULL", (self.ns, path))
            r = cur.fetchone()
            if r is None:
                return None
            return r[0]

    @override
    def write_file(self, path: str, content: str):
        with self._cursor() as cur:
            target_path = pathlib.Path(path)
            self._mkdirs(cur, path=target_path.parent)
            cur.execute(f"""
INSERT INTO memories_fs(namespace, full_path, entry_name, parent_path, is_directory, contents) VALUES
({self.pos_placeholder}, {self.pos_placeholder}, {self.pos_placeholder}, {self.pos_placeholder}, False, {self.pos_placeholder})
ON CONFLICT(namespace, full_path) DO UPDATE SET contents = excluded.contents
                        """, (self.ns, path, target_path.name, str(target_path.parent), content))

    @override
    def stat(self, path: str) -> MemoryBackend.FileStat:
        with self._cursor() as cur:
            cur.execute(f"SELECT is_directory FROM memories_fs WHERE namespace = {self.pos_placeholder} AND full_path = {self.pos_placeholder}", (self.ns, path))
            r = cur.fetchone()
            if not r:
                return MemoryBackend.FileStat(exists=False, is_dir=False)
            return MemoryBackend.FileStat(exists=True, is_dir=r[0])


class PostgresMemoryBackend(SQLBackend[Connection]):
    def __init__(self, ns: str, conn: Connection):
        super().__init__(ns, conn)

    @property
    def pos_placeholder(self) -> str:
        return "%s"

    @override
    def named_placeholder(self, nm: str) -> str:
        return f"%({nm})s"

    @contextmanager
    def _cursor(self) -> Iterator['DBCursor']:
        """
        Override to handle PostgreSQL connection properly.
        Don't use the connection as context manager to avoid closing it.
        Instead, manually manage transactions per cursor operation.
        """
        cur = self.conn.cursor()
        try:
            yield cur
            self.conn.commit()
        except Exception:
            self.conn.rollback()
            raise
        finally:
            cur.close()

    @override
    def _setup(self):
        with self.conn.cursor() as curr:
            curr.execute("""
CREATE TABLE IF NOT EXISTS memories_fs(
    namespace TEXT NOT NULL,
    entry_name TEXT NOT NULL,
    full_path TEXT,
    parent_path TEXT,
    is_directory BOOL NOT NULL,
    contents TEXT,
    FOREIGN KEY(parent_path, namespace) REFERENCES memories_fs(full_path, namespace) ON DELETE CASCADE, -- good hierarchy
    UNIQUE (namespace, full_path), -- unique paths within ns
    UNIQUE (namespace, parent_path, entry_name), -- unique names within directories
    CHECK (parent_path is NOT NULL OR (full_path = '/memories' AND is_directory AND entry_name = 'memories')),
    CHECK (parent_path is NULL OR (full_path = concat(parent_path, '/', entry_name))), -- entry, path consistency
    CHECK (contents IS NOT NULL != is_directory)
);

CREATE INDEX IF NOT EXISTS memories_namespace_path ON memories_fs(namespace, full_path text_pattern_ops); -- text pattern ops lets us use the index for LIKE
                         """)
        self.conn.commit()

    @override
    def do_replace_first(self, replace_attribute: str, to_replace: str, replace_with: str, seq: int) -> tuple[str, dict[str, str]]:
        replace_quot = re.escape(to_replace)
        src_patt_p = f"replace_src{seq}"
        replace_str_p = f"replace_dst{seq}"
        return (f"regexp_replace({replace_attribute}, %({src_patt_p})s, %({replace_str_p})s)", {
            src_patt_p: "^" + replace_quot,
            replace_str_p: replace_with
        })

    def __del__(self):
        try:
            if hasattr(self, 'conn') and self.conn and not self.conn.closed:
                # Commit any pending transaction before closing
                if not self.conn.autocommit:
                    try:
                        self.conn.commit()
                    except Exception:
                        pass
        except Exception:
            pass


class SqliteMemoryBackend(SQLBackend[sqlite3.Connection]):
    def __init__(self, ns: str, conn: sqlite3.Connection):
        super().__init__(ns, conn)

    @property
    def pos_placeholder(self) -> str:
        return '?'

    @override
    def named_placeholder(self, nm: str) -> str:
        return f":{nm}"

    @override
    def _setup(self):
        with self.conn:
            cur = self.conn.cursor()
            cur.executescript("""
CREATE TABLE IF NOT EXISTS memories_fs(
    namespace TEXT NOT NULL,
    entry_name TEXT NOT NULL,
    full_path TEXT COLLATE NOCASE, -- required so LIKE uses the index
    parent_path TEXT,
    is_directory BOOL NOT NULL,
    contents TEXT,
    FOREIGN KEY(parent_path, namespace) REFERENCES memories_fs(full_path, namespace) ON DELETE CASCADE, -- good hierarchy
    UNIQUE (namespace, full_path), -- unique paths within ns
    UNIQUE (namespace, parent_path, entry_name), -- unique names within directories
    CHECK (parent_path is NOT NULL OR (full_path = '/memories' AND is_directory AND entry_name = 'memories')),
    CHECK (parent_path is NULL OR (full_path = concat(parent_path, '/', entry_name))), -- entry, path consistency
    CHECK (contents IS NOT NULL != is_directory)
);

CREATE INDEX IF NOT EXISTS memories_namespace_path ON memories_fs(namespace, full_path);
                              """)
        self.conn.create_function("replace_first", 3, self._replace_first, deterministic=True)
        self.conn.execute("PRAGMA foreign_keys = ON;")  # easy trip up!

    def _replace_first(self, target, src_str, result) -> str:
        return str(target).replace(str(src_str), str(result), 1)

    @override
    def do_replace_first(self, replace_attribute: str, to_replace: str, replace_with: str, seq: int) -> tuple[str, dict[str, str]]:
        src_p = f"replace_src_{seq}"
        dst_p = f"replace_dst_{seq}"
        return (f"replace_first({replace_attribute}, :{src_p}, :{dst_p})", {
            src_p: to_replace,
            dst_p: replace_with
        })


class FileSystemMemoryBackend(MemoryBackend):
    """
    A simple backend that "mounts" `/memories` to the storage folder given as the constructor arg.
    """
    def __init__(self, storage_folder: pathlib.Path):
        self.memory_root = storage_folder

    def _relativize(self, path: str) -> pathlib.Path:
        r = pathlib.Path(path).relative_to("/memories")
        return (self.memory_root / r).resolve()

    @override
    def stat(self, path: str) -> MemoryBackend.FileStat:
        actual = self._relativize(path)
        if not actual.exists():
            return MemoryBackend.FileStat(exists=False, is_dir=False)
        return MemoryBackend.FileStat(exists=True, is_dir=actual.is_dir())

    @override
    def write_file(self, path: str, content: str):
        r = self._relativize(path)
        r.parent.mkdir(parents=True, exist_ok=True)
        r.write_text(content)

    @override
    def read_file(self, path: str) -> str | None:
        r = self._relativize(path)
        if not r.is_file():
            return None
        return r.read_text()

    @override
    def do_rename(self, old_path: str, new_path: str) -> str:
        old_full_path = self._relativize(old_path)
        new_full_path = self._relativize(new_path)

        new_full_path.parent.mkdir(parents=True, exist_ok=True)
        old_full_path.rename(new_full_path)

        return f"Renamed {old_path} -> {new_path}"

    @override
    def rm(self, path: str) -> str:
        r = self._relativize(path)
        if r.is_dir():
            shutil.rmtree(r)
        else:
            r.unlink()
        return f"Removed {path}"

    @override
    def list_dir(self, path: str) -> Iterable[tuple[str, bool]]:
        r = self._relativize(path)
        for it in r.iterdir():
            yield (str(it.relative_to(r)), it.is_dir())

def memory_tool(backend: MemoryBackend) -> BaseTool:
    """
    Generates a memory tool using the given backend.
    """
    def missing_required(s: str):
        return f"Error: missing required {s} argument"

    @tool(args_schema=UnifiedMemorySchema)
    def memory(
        command: Literal["view", "create", "str_replace", "insert", "delete", "rename"],
        path: Optional[str] = None,
        view_range: Optional[list[int]] = None,
        file_text: Optional[str] = None,

        old_str: Optional[str] = None,
        new_str: Optional[str] = None,

        insert_line: Optional[int] = None,
        insert_text: Optional[str] = None,


        old_path: Optional[str] = None,
        new_path: Optional[str] = None,
    ) -> str:
        """
        The actual implementation of the various memory subcommands expected by claude's memory tool.
        """
        try:
            match command:
                case "create":
                    if path is None:
                        return missing_required("path")
                    elif file_text is None:
                        return missing_required("file_text")
                    return backend.create(path, file_text)

                case "delete":
                    if path is None:
                        return missing_required("path")
                    return backend.delete(path)

                case "insert":
                    if path is None:
                        return missing_required("path")
                    elif insert_line is None:
                        return missing_required("insert_line")
                    elif insert_text is None:
                        return missing_required("insert_text")
                    return backend.insert(path, insert_line, insert_text)
                case "rename":
                    if old_path is None:
                        return missing_required("old_path")
                    elif new_path is None:
                        return missing_required("new_path")
                    return backend.rename(old_path, new_path)

                case "str_replace":
                    if path is None:
                        return missing_required("path")
                    elif old_str is None:
                        return missing_required("old_str")
                    elif new_str is None:
                        return missing_required("new_str")
                    return backend.str_replace(path, old_str, new_str)

                case "view":
                    if path is None:
                        return missing_required("path")
                    range : tuple[int, int] | None = None
                    if view_range is not None and len(view_range) >= 2:
                        range = (view_range[0], view_range[1])
                    return backend.view(path, range)
        except MemoryBackendError as e:
            return f"Backend error: {str(e)}"

    return memory
