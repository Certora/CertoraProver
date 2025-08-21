use solana_program::{
    account_info::AccountInfo,
    program::{invoke, invoke_signed},
    program_error::ProgramError,
};

use spl_token::instruction::{burn, close_account, mint_to, transfer};

const SEED1: &[u8] = b"seed1";
const SEED2: &[u8] = b"seed2";
const SEED3: &[u8] = b"seed3";

pub fn process_transfer_token<const N_SIGNERS: usize>(
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
    invoke_transfer_token::<N_SIGNERS>(token_program, from, to, authority, amount)?;
    Ok(())
}

fn invoke_transfer_token<'a, const N_SIGNERS: usize>(
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

    match N_SIGNERS {
        0 => invoke(&instruction, &[from.clone(), to.clone(), authority.clone()])?,
        1 => invoke_signed(
            &instruction,
            &[from.clone(), to.clone(), authority.clone()],
            &[&[SEED1]],
        )?,
        2 => invoke_signed(
            &instruction,
            &[from.clone(), to.clone(), authority.clone()],
            &[&[SEED1], &[SEED2]],
        )?,
        3 => invoke_signed(
            &instruction,
            &[from.clone(), to.clone(), authority.clone()],
            &[&[SEED1], &[SEED2], &[SEED3]],
        )?,
        _ => return Err(ProgramError::InvalidArgument),
    }
    Ok(())
}

pub fn process_mint_token<const N_SIGNERS: usize>(
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

    invoke_mint_token::<N_SIGNERS>(token_program, mint, destination, mint_authority, amount)?;
    Ok(())
}

fn invoke_mint_token<'a, const N_SIGNERS: usize>(
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
    match N_SIGNERS {
        0 => invoke(
            &instruction,
            &[mint.clone(), destination.clone(), mint_authority.clone()],
        )?,
        1 => invoke_signed(
            &instruction,
            &[mint.clone(), destination.clone(), mint_authority.clone()],
            &[&[SEED1]],
        )?,
        2 => invoke_signed(
            &instruction,
            &[mint.clone(), destination.clone(), mint_authority.clone()],
            &[&[SEED1], &[SEED2]],
        )?,
        3 => invoke_signed(
            &instruction,
            &[mint.clone(), destination.clone(), mint_authority.clone()],
            &[&[SEED1], &[SEED2], &[SEED3]],
        )?,
        _ => return Err(ProgramError::InvalidArgument),
    }
    Ok(())
}

pub fn process_burn_token<const N_SIGNERS: usize>(
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

    invoke_burn_token::<N_SIGNERS>(token_program, source, mint, authority, amount)?;
    Ok(())
}

fn invoke_burn_token<'a, const N_SIGNERS: usize>(
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
    match N_SIGNERS {
        0 => invoke(
            &instruction,
            &[source.clone(), mint.clone(), authority.clone()],
        )?,
        1 => invoke_signed(
            &instruction,
            &[source.clone(), mint.clone(), authority.clone()],
            &[&[SEED1]],
        )?,
        2 => invoke_signed(
            &instruction,
            &[source.clone(), mint.clone(), authority.clone()],
            &[&[SEED1], &[SEED2]],
        )?,
        3 => invoke_signed(
            &instruction,
            &[source.clone(), mint.clone(), authority.clone()],
            &[&[SEED1], &[SEED2], &[SEED3]],
        )?,
        _ => return Err(ProgramError::InvalidArgument),
    }
    Ok(())
}

pub fn process_close_account<const N_SIGNERS: usize>(
    accounts: &[AccountInfo],
    _instruction_data: &[u8],
) -> Result<(), ProgramError> {
    let token_program = &accounts[0];
    let account = &accounts[1];
    let dest = &accounts[2];
    let owner = &accounts[3];
    invoke_close_account::<N_SIGNERS>(token_program, account, dest, owner)?;
    Ok(())
}

fn invoke_close_account<'a, const N_SIGNERS: usize>(
    token_program: &AccountInfo<'a>,
    account: &AccountInfo<'a>,
    dest: &AccountInfo<'a>,
    owner: &AccountInfo<'a>,
) -> Result<(), ProgramError> {
    let instruction = close_account(token_program.key, account.key, dest.key, owner.key, &[])?;
    match N_SIGNERS {
        0 => invoke(
            &instruction,
            &[account.clone(), dest.clone(), owner.clone()],
        )?,
        1 => invoke_signed(
            &instruction,
            &[account.clone(), dest.clone(), owner.clone()],
            &[&[SEED1]],
        )?,
        2 => invoke_signed(
            &instruction,
            &[account.clone(), dest.clone(), owner.clone()],
            &[&[SEED1], &[SEED2]],
        )?,
        3 => invoke_signed(
            &instruction,
            &[account.clone(), dest.clone(), owner.clone()],
            &[&[SEED1], &[SEED2], &[SEED3]],
        )?,
        _ => return Err(ProgramError::InvalidArgument),
    }
    Ok(())
}
