#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.
import dataclasses
import json
from enum import Enum
from typing import Optional, Any
from pathlib import Path
import sys

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

import CertoraProver.certoraContextAttributes as Attrs
from CertoraProver.certoraCollectRunMetadata import RunMetaData, MetadataEncoder
import Shared.certoraUtils as Utils
from typing import List


class MainSection(Enum):
    GENERAL = "GENERAL"
    OPTIONS = "OPTIONS"
    SOLIDITY_COMPILER = "SOLIDITY_COMPILER"
    NEW_SECTION = "NEW_SECTION"


class ContentType(Enum):
    SIMPLE = "SIMPLE"
    COMPLEX = "COMPLEX"
    FLAG = "FLAG"


@dataclasses.dataclass
class InnerContent:
    inner_title: str
    content_type: str
    content: Any
    doc_link: str = ''
    tooltip: str = ''
    unsound: bool = False

    def __post_init__(self) -> None:
        if isinstance(self.content, bool):
            self.content = 'true' if self.content else 'false'


@dataclasses.dataclass
class CardContent:
    card_title: str
    content_type: str
    content: Any


DOC_LINK_PREFIX = 'https://docs.certora.com/en/latest/docs/'
GIT_ATTRIBUTES = ['origin', 'revision', 'branch', 'dirty']
ARG_LIST_ATTRIBUTES = ['prover_args', 'java_args']


class AttributeJobConfigData:
    """
    Collect information about attribute configuration presented in the Config tab of the Rule Report.
    This should be added to the AttributeDefinition and configured for every new attribute
    presented in the Rule report.

    Note: Attributes that do not contain specific information will be presented in the OPTIONS main section!

    arguments:
    - main_section : MainSection -- the main section inside the config tab
        default: MainSection.OPTIONS
    - subsection : str -- the subsection within the main_section
        default: None - they will be presented inside the OPTIONS card
    - doc_link : Optional[str] -- a link to the Documentation page of this attribute (if exists)
        default: 'https://docs.certora.com/en/latest/docs/' + Solana/EVM path + #<attribute_name>
    - tooltip : Optional[str] -- a description of this attribute to present in the config tab
        default: ''
    - unsound : bool -- an indicator if this attribute is sound or potentially unsound
        default: False
    """

    def __init__(self, main_section: MainSection = MainSection.OPTIONS, subsection: str = '',
                 doc_link: Optional[str] = '', tooltip: Optional[str] = '', unsound: bool = False):
        self.main_section = main_section
        self.subsection = subsection
        self.doc_link = doc_link
        self.tooltip = tooltip
        self.unsound = unsound


class RunConfigurationLayout:
    """
    Collect information about run configuration presented in the Config tab of the Rule Report.
    RunConfigData is aggregated from conf attributes, cmd arguments and metadata provided as input.

    arguments:
    configuration_layout : Dict -- An aggregated configuration for a specific run, nested by main section, subsection.
        Each leaf contains data about attribute value, type, documentation link and UI data.
    """

    configuration_layout: list[Any]

    def __init__(self, configuration_layout: list[Any]):
        # Dynamically allocate class attributes from dict
        self.configuration_layout = configuration_layout

    def __repr__(self) -> str:
        try:
            return json.dumps(self.configuration_layout, indent=2, sort_keys=True)
        except TypeError:
            # Fallback if something isn't serializable
            return str(self.configuration_layout)

    @classmethod
    def dump_file(cls, data: list) -> None:
        sorted_data = sort_configuration_layout(data)
        with Utils.get_configuration_layout_data_file().open("w+") as f:
            json.dump(sorted_data, f, indent=4, cls=MetadataEncoder)

    @classmethod
    def load_file(cls) -> dict:
        try:
            with Utils.get_configuration_layout_data_file().open() as f:
                return json.load(f)
        except Exception as e:
            print(f"failed to load configuration layout file {Utils.get_configuration_layout_data_file()}\n{e}")
            raise

    def dump(self) -> None:
        try:
            self.dump_file(self.configuration_layout)
        except Exception as e:
            print(f"Failed to write configuration layout file: {Utils.get_configuration_layout_data_file()}\n{e}")
            raise


def collect_configuration_layout() -> RunConfigurationLayout:
    """
    Collect information about run metadata and uses it to create RunConfigurationLayoutData object
    If loading metadata fails, collecting configuration layout will fail as well and return an empty object.
    """
    try:
        metadata = RunMetaData.load_file()
    except Exception as e:
        print(f"failed to load job metadata! cannot create a configuration layout file without metadata!\n{e}")
        return RunConfigurationLayout(configuration_layout=[])

    attributes_configs = collect_attribute_configs(metadata)
    configuration_layout = collect_run_config_from_metadata(attributes_configs, metadata)

    return RunConfigurationLayout(configuration_layout=configuration_layout)


def get_doc_link(attr) -> str:  # type: ignore
    """
    Build dynamically a link to a specific attribute in Certora Documentation based on the attribute application.
    arguments:
    - attr: Attrs.AttributeDefinition -- current attribute to build a Documentation link for
    returns:
    - str -- a link to the correct attribute's Documentation link
    """

    # Once Soroban will have proper documentation we would need to adjust the suffix link.
    rust_suffix = Attrs.is_rust_app() and (attr.name in Attrs.SolanaProverAttributes.__dict__ or
                                           attr.name in Attrs.RustAttributes.__dict__)

    doc_link_suffix = 'solana/' if rust_suffix else 'prover/cli/'
    doc_link = f'{DOC_LINK_PREFIX}{doc_link_suffix}options.html#{attr.name.lower().replace("_", "-")}'

    return doc_link


def create_or_get_card_content(output: list[CardContent], name: str) -> CardContent:
    """
        Returns an existing CardContent by name or creates and appends a new one if it doesn't exist.
        Card content type will always be complex in this case.
        Args:
            output (list[CardContent]): List of CardContent objects.
            name (str): Title of the card to find or create.

        Returns:
            CardContent: The found or newly created CardContent.
        """
    main_section = next((section for section in output if section.card_title == name), None)
    if main_section is None:
        main_section = CardContent(
            card_title=name,
            content_type=ContentType.COMPLEX.value,
            content=[]
        )
        output.append(main_section)
    return main_section


def split_and_sort_arg_list_value(args_list: List[str]) -> List[str]:
    """
    Splits a unified CLI argument list of strings into a sorted list of flag+value groups.
    This is useful mainly for --prover_args and --java_args.

    For example:
    "-depth 15 -adaptiveSolverConfig false" → ["-adaptiveSolverConfig false", "-depth 15"]

    Assumes each flag starts with '-' and its value follows immediately, if exists.
    Lines are sorted alphabetically.
    """
    unified_args = ' '.join(str(arg) for arg in args_list)

    if not unified_args.strip():
        return []

    lines: List[str] = []
    tokens = unified_args.split()
    curr_line = ""

    for token in tokens:
        if token.startswith('-'):
            if curr_line:
                lines.append(curr_line)
            curr_line = token
        else:
            curr_line += f" {token}"

    if curr_line:
        lines.append(curr_line)

    return sorted(lines)


def create_inner_content(name: str, content_type: ContentType, value: Any, doc_link: str,
                         config_data: AttributeJobConfigData) -> InnerContent:
    return InnerContent(
        inner_title=name,
        content_type=content_type.value,
        content=value,
        doc_link=doc_link,
        tooltip=config_data.tooltip or '',
        unsound=config_data.unsound
    )


def collect_attribute_configs(metadata: dict) -> list[CardContent]:
    """
    Collects and organizes attribute configurations into a structured list of CardContent objects.

    This function iterates through all available attributes defined, checks if relevant metadata is provided
    for each attribute, and organizes the data into sections and subsections based on configuration rules.

    Attributes are grouped under their respective main sections, with special handling for:
    - Simple value attributes
    - List and dictionary attributes
    - Attributes requiring new sections (e.g., Files, Links, Packages)

    Args:
        metadata (dict): Metadata dictionary containing attribute values.

    Returns:
        list: A list of CardContent objects representing the structured configuration view,
              ready for rendering or further processing.
    """
    attr_list = Attrs.get_attribute_class().attribute_list()
    output: list[CardContent] = []

    for attr in attr_list:
        attr_name = attr.name.lower()
        if attr.config_data is None:
            continue

        attr_value = metadata.get(attr_name) or metadata.get('conf', {}).get(attr_name)
        if attr_value is None:
            continue

        config_data: AttributeJobConfigData = attr.config_data
        doc_link = config_data.doc_link or get_doc_link(attr)

        # Find or create the main section
        main_section_key = config_data.main_section.value.lower()
        main_section = create_or_get_card_content(output, main_section_key)

        # Files, Links and Packages are special cases where the main section is the attribute itself
        if main_section_key == MainSection.NEW_SECTION.value.lower():
            main_section.card_title = attr_name
            main_section.content_type = ContentType.SIMPLE.value
            main_section.content.append(
                create_inner_content(attr_name, ContentType.SIMPLE, attr_value, doc_link, config_data)
            )
            continue

        # Find or create the subsection (if it doesn't exist)
        if isinstance(attr_value, list):
            content_type = ContentType.SIMPLE
            if attr_name in ARG_LIST_ATTRIBUTES:
                attr_value = split_and_sort_arg_list_value(attr_value)

        elif isinstance(attr_value, dict):
            content_type = ContentType.COMPLEX
            attr_value = [
                create_inner_content(key, ContentType.FLAG, value, doc_link, config_data)
                for key, value in attr_value.items()
            ]
        else:
            content_type = ContentType.FLAG

        # Update the current section with attribute details
        main_section.content.append(
            create_inner_content(attr_name, content_type, attr_value, doc_link, config_data)
        )

    return output


def collect_run_config_from_metadata(attributes_configs: list[CardContent], metadata: dict) -> list[CardContent]:
    """
    Adding CLI and Git configuration from metadata
    """

    general_section = create_or_get_card_content(attributes_configs, MainSection.GENERAL.value.lower())

    if cli_version := metadata.get('CLI_version'):
        general_section.content.append(InnerContent(
            inner_title='CLI Version',
            content_type=ContentType.FLAG.value,
            content=cli_version,
        ))

    for attr in GIT_ATTRIBUTES:
        if attr_value := metadata.get(attr):
            general_section.content.append(InnerContent(
                inner_title=attr,
                content_type=ContentType.FLAG.value,
                content=attr_value,
            ))

    return attributes_configs


def sort_configuration_layout(data: list[CardContent]) -> list[CardContent]:
    """
    Sorts a configuration layout:
    - Top-level sorted by 'card_title'
    - Nested content sorted by 'inner_title', with 'verify' first
    """
    priority = {
        # Priorities for top-level cards
        "general": 0,
        "files": 1,
        "options": 2,
        # Top level items inside their respective cards
        "verify": 0,
        "solc": 0,
        "CLI Version": 0
    }

    def inner_sort_key(item: Any) -> Any:
        if isinstance(item, CardContent):
            title = item.card_title
            return priority.get(title, 3), title.lower()
        elif isinstance(item, InnerContent):
            title = item.inner_title
            return priority.get(title, 3), title.lower()
        else:
            return item

    def sort_content(content: list[InnerContent]) -> list[InnerContent]:
        sorted_content = []
        for item in content:
            if isinstance(item.content, list):
                # Recurse into nested 'content'
                item.content = sorted(item.content, key=inner_sort_key)
            sorted_content.append(item)
        return sorted(sorted_content, key=inner_sort_key)

    # Sort top-level entries by 'card_title'
    sorted_data = sorted(data, key=inner_sort_key)

    # Sort nested 'content'
    for section in sorted_data:
        section.content = sort_content(section.content)

    return sorted_data
