use solana_program::{account_info::AccountInfo, program::invoke, program_error::ProgramError};

#[cfg(not(feature = "certora"))]
use spl_token::instruction::{burn, close_account, mint_to, transfer};

#[cfg(feature = "certora")]
use cvlr_solana::cpis::{burn, close_account, mint_to, transfer};

pub fn process_transfer_token(
    accounts: &[AccountInfo],
    instruction_data: &[u8],
) -> Result<(), ProgramError> {
    let token_program = &accounts[0];
    let from = &accounts[1];
    let to = &accounts[2];
    let authority = &accounts[3];
    let amount = u64::from_le_bytes(
        instruction_data[..8]
            .try_into()
            .expect("Invalid slice length"),
    );
    invoke_transfer_token(token_program, from, to, authority, amount)?;
    Ok(())
}

#[cvlr::early_panic]
fn invoke_transfer_token<'a>(
    token_program: &AccountInfo<'a>,
    from: &AccountInfo<'a>,
    to: &AccountInfo<'a>,
    authority: &AccountInfo<'a>,
    amount: u64,
) -> Result<(), ProgramError> {
    // Observe that with conditional compilation, this is fetched from cvlr_solana.
    let instruction = transfer(
        token_program.key,
        from.key,
        to.key,
        authority.key,
        &[],
        amount,
    )?;
    let account_infos = vec![from.clone(), to.clone(), authority.clone()];
    invoke(&instruction, &account_infos)?;
    Ok(())
}

pub fn process_mint_token(
    accounts: &[AccountInfo],
    instruction_data: &[u8],
) -> Result<(), ProgramError> {
    let token_program = &accounts[0];
    let mint = &accounts[1];
    let destination = &accounts[2];
    let mint_authority = &accounts[3];

    let amount = u64::from_le_bytes(
        instruction_data[..8]
            .try_into()
            .expect("Invalid slice length"),
    );

    invoke_mint_token(token_program, mint, destination, mint_authority, amount)?;
    Ok(())
}

#[cfg_attr(feature = "certora", cvlr::early_panic)]
fn invoke_mint_token<'a>(
    token_program: &AccountInfo<'a>,
    mint: &AccountInfo<'a>,
    destination: &AccountInfo<'a>,
    mint_authority: &AccountInfo<'a>,
    amount: u64,
) -> Result<(), ProgramError> {
    let instruction = mint_to(
        token_program.key,
        mint.key,
        destination.key,
        mint_authority.key,
        &[],
        amount,
    )?;
    invoke(
        &instruction,
        &[mint.clone(), destination.clone(), mint_authority.clone()],
    )?;
    Ok(())
}

pub fn process_burn_token(
    accounts: &[AccountInfo],
    instruction_data: &[u8],
) -> Result<(), ProgramError> {
    let token_program = &accounts[0];
    let source = &accounts[1];
    let mint = &accounts[2];
    let authority = &accounts[3];

    let amount = u64::from_le_bytes(
        instruction_data[..8]
            .try_into()
            .expect("Invalid slice length"),
    );

    invoke_burn_token(token_program, source, mint, authority, amount)?;
    Ok(())
}

#[cvlr::early_panic]
fn invoke_burn_token<'a>(
    token_program: &AccountInfo<'a>,
    source: &AccountInfo<'a>,
    mint: &AccountInfo<'a>,
    authority: &AccountInfo<'a>,
    amount: u64,
) -> Result<(), ProgramError> {
    let instruction = burn(
        token_program.key,
        source.key,
        mint.key,
        authority.key,
        &[],
        amount,
    )?;
    invoke(
        &instruction,
        &[source.clone(), mint.clone(), authority.clone()],
    )?;
    Ok(())
}

#[cvlr::early_panic]
pub fn process_close_account(
    accounts: &[AccountInfo],
    _instruction_data: &[u8],
) -> Result<(), ProgramError> {
    let token_program = &accounts[0];
    let account = &accounts[1];
    let dest = &accounts[2];
    let owner = &accounts[3];
    invoke_close_account(token_program, account, dest, owner)?;
    Ok(())
}

#[cvlr::early_panic]
fn invoke_close_account<'a>(
    token_program: &AccountInfo<'a>,
    account: &AccountInfo<'a>,
    dest: &AccountInfo<'a>,
    owner: &AccountInfo<'a>,
) -> Result<(), ProgramError> {
    let instruction = close_account(token_program.key, account.key, dest.key, owner.key, &[])?;
    invoke(
        &instruction,
        &[account.clone(), dest.clone(), owner.clone()],
    )?;
    Ok(())
}
