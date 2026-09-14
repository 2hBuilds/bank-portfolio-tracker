package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The last bank the plugin saw, for one account on one profile (contract C5). Persisted so the panel is
 * useful at the Grand Exchange and on the login screen: the file is read at start-up and the rows are drawn
 * with "Bank as of HH:MM" long before - or entirely without - a bank visit (design D7).
 *
 * <p>Public fields, no-arg constructor, plain types: the injected Gson writes and reads it directly
 * (playbook step 13). {@link #normalize()} is the single place a loaded file is made safe, so every reader
 * downstream may assume a non-null item list, non-null names and positive ids and quantities.
 *
 * <p>Since addendum Y line Y2 the same file carries what the player was CARRYING and WEARING at the last read
 * ({@link #inventory}, {@link #worn}, {@link #carriedGp}, {@link #carriedAtMillis}, put there by
 * {@link #withCarried(BankReader.Carried)}), so "Include inventory and worn gear" can be flipped on and off without
 * a bank visit - the same reasoning that keeps {@link #currencyGp} and every untradeable stack in every capture.
 * Older files have none of it and read as "carrying nothing".
 *
 * <p>The account hash and profile type are stored INSIDE the file as well as in its name
 * ({@code bank-<accountHash>-<profileType>.json}, contract C17) so a file that has been renamed or copied can
 * still be told apart from the current account - the service checks them before showing a snapshot as this
 * account's bank (C23).
 */
public class BankSnapshot
{
	/**
	 * The profile type used when a file names none - RuneLite's ordinary main-game profile
	 * ({@code RuneScapeProfileType.STANDARD}).
	 */
	public static final String DEFAULT_PROFILE_TYPE = "STANDARD";

	/**
	 * The "no bank captured" snapshot, which the panel renders as "Open your bank once to load your items".
	 *
	 * <p>Shared and to be treated as READ-ONLY: this class is a persisted bag of public fields, so nothing but
	 * discipline stops a caller mutating it. Its item list is {@link Collections#emptyList()} for that reason.
	 */
	public static final BankSnapshot EMPTY = new BankSnapshot(Collections.emptyList(), 0L, 0L, DEFAULT_PROFILE_TYPE);

	/**
	 * The stacks, one per canonical item id. Never null after {@link #normalize()}.
	 */
	public List<BankItem> items;

	/**
	 * When the bank was captured, epoch millis; 0 means "never" ({@link #EMPTY}).
	 */
	public long capturedAtMillis;

	/**
	 * {@code Client.getAccountHash()} at capture time - the account this bank belongs to.
	 */
	public long accountHash;

	/**
	 * {@code RuneScapeProfileType.getCurrent(client).name()} at capture time: a main-game bank and a
	 * Leagues/DMM bank are different banks and never share a file.
	 */
	public String profileType;

	/**
	 * What the bank's COINS and PLATINUM TOKENS are worth, in gp: {@code coins x 1 + platinum x 1000}, exactly
	 * the valuation RuneLite's own item manager hard-codes for the two (clone
	 * {@code runelite-client/src/main/java/net/runelite/client/game/ItemManager.java:328-337}). Clamped at
	 * {@link Long#MAX_VALUE} rather than wrapped.
	 *
	 * <p>Currency is NOT one of {@link #items}, and this field is why both can be true at once (B097). Design
	 * D6 keeps it out of the ROWS because it has no market - a movement row for coins would read 0 % for ever -
	 * but it is half of what a player means by "bank value", and a total that leaves it out disagrees with the
	 * figure RuneLite's own bundled Bank plugin puts in the bank title bar by exactly the player's cash.
	 * {@link BankReader#currencyWorth} is the one place the sum is defined.
	 *
	 * <p><b>Persistence caveat.</b> This is a Gson bag of public fields, so a {@code bank-*.json} written before
	 * the field existed reads back as 0: a REMEMBERED bank (the login-screen and Grand Exchange case of D7) shows
	 * no currency at all until the player next opens a bank. Nothing repairs that, and nothing should - the old
	 * file genuinely does not know.
	 */
	public long currencyGp;

	/**
	 * The stacks in the player's INVENTORY at the last carried read (addendum Y, line Y2), one per canonical id and
	 * by exactly the rules {@link #items} follows. Never null after {@link #normalize()}; EMPTY in every
	 * {@code bank-*.json} written before addendum Y, and empty whenever the switch has never been on with a client
	 * logged in - "we have read nothing" and "you were carrying nothing" are the same thing to every reader, because
	 * both mean there is nothing to add.
	 */
	public List<BankItem> inventory;

	/** The stacks on the player's WORN gear at that same read (Y2), by the same rules. Never null after normalize. */
	public List<BankItem> worn;

	/**
	 * What the coins and platinum tokens the player was CARRYING are worth, in gp - the carried twin of
	 * {@link #currencyGp}, kept apart from it so that turning "Include inventory and worn gear" off takes exactly
	 * the carried cash back out of the bank value and leaves the bank's own alone. 0 in a file written before
	 * addendum Y.
	 */
	public long carriedGp;

	/**
	 * When the two carried containers were read, epoch millis; 0 means "never" - which is what a pre-Y file and a
	 * snapshot captured with no client logged in both say.
	 *
	 * <p>Its own stamp rather than {@link #capturedAtMillis}, because the two really are read at different moments:
	 * Refresh re-reads what the player carries and republishes the STORED bank beside it (Y2), so the footnote's
	 * "bank HH:MM" clock has to keep naming the bank's own read.
	 */
	public long carriedAtMillis;

	public BankSnapshot()
	{
		items = new ArrayList<>();
		profileType = DEFAULT_PROFILE_TYPE;
		inventory = new ArrayList<>();
		worn = new ArrayList<>();
	}

	public BankSnapshot(final List<BankItem> items, final long capturedAtMillis, final long accountHash,
		final String profileType)
	{
		this(items, capturedAtMillis, accountHash, profileType, 0L);
	}

	public BankSnapshot(final List<BankItem> items, final long capturedAtMillis, final long accountHash,
		final String profileType, final long currencyGp)
	{
		this(items, capturedAtMillis, accountHash, profileType, currencyGp, Collections.emptyList(),
			Collections.emptyList(), 0L, 0L);
	}

	/**
	 * The full shape since addendum Y: a bank part and a carried part, each with its own cash and its own clock.
	 *
	 * @param inventory       the inventory's stacks; null becomes an empty list
	 * @param worn            the worn gear's stacks; null becomes an empty list
	 * @param carriedGp       the coins and platinum tokens in hand, in gp
	 * @param carriedAtMillis when the two carried containers were read; 0 = never
	 */
	public BankSnapshot(final List<BankItem> items, final long capturedAtMillis, final long accountHash,
		final String profileType, final long currencyGp, final List<BankItem> inventory, final List<BankItem> worn,
		final long carriedGp, final long carriedAtMillis)
	{
		this.items = items;
		this.capturedAtMillis = capturedAtMillis;
		this.accountHash = accountHash;
		this.profileType = profileType;
		this.currencyGp = currencyGp;
		this.inventory = inventory == null ? Collections.<BankItem>emptyList() : inventory;
		this.worn = worn == null ? Collections.<BankItem>emptyList() : worn;
		this.carriedGp = carriedGp;
		this.carriedAtMillis = carriedAtMillis;
	}

	/**
	 * This same BANK with a fresh carried part (addendum Y, line Y2) - what Refresh publishes: the stored stacks,
	 * their capture time, their cash and their account untouched, and the inventory, the worn gear, the carried cash
	 * and the carried clock replaced by what the client has just been asked for.
	 *
	 * <p>A copy rather than a mutation, because the snapshot the service holds is handed to the executor and the
	 * EDT and must never change under them; the item list is shared by reference, which is safe for the same reason
	 * {@code setBank}'s is - nothing mutates a captured list.
	 *
	 * @param carried what the player carries and wears; null clears the carried part, which is what a read with no
	 *                client logged in would have found
	 */
	public BankSnapshot withCarried(final BankReader.Carried carried)
	{
		final BankReader.Carried next = carried == null ? BankReader.Carried.EMPTY : carried;
		return new BankSnapshot(items, capturedAtMillis, accountHash, profileType, currencyGp, next.inventory,
			next.worn, next.carriedGp, next.readAtMillis);
	}

	/**
	 * True when there are no priceable ROWS to show. {@link #currencyGp} is deliberately not consulted: a bank of
	 * nothing but coins has no movement rows to draw, which is the question every caller asks this. What the card
	 * at the top of a row-less bank should say about the coins in it is the panel's call, made on the row count.
	 */
	public boolean isEmpty()
	{
		return items == null || items.isEmpty();
	}

	/**
	 * The same bank, of the same account and profile, whatever MOMENT the two were captured at - which
	 * {@link #equals(Object)} deliberately does not answer, because a persisted snapshot has to be told apart
	 * from a re-read of it.
	 *
	 * <p>Why it exists: OSRS posts one {@code ItemContainerChanged} per change to the bank container, and a great
	 * many of those carry an identical stack list - the plugin's own start-up replay of the cached container, a
	 * bank re-opened without a change, a placeholder shuffled. Each one used to cost a full serialisation of
	 * every stack plus a temp file and an atomic replace on the client's single shared executor. The service
	 * compares with this and skips the write when nothing about the CONTENT moved; the price of that is a
	 * persisted {@code capturedAtMillis} that keeps the first capture time of an unchanged bank, which is the
	 * honest stamp for it anyway.
	 *
	 * <p>{@link #currencyGp} counts as content (B097): a player who deposits their coins and nothing else has
	 * changed their bank, and a comparison that ignored the currency would leave the new total unwritten until
	 * some unrelated stack happened to move.
	 *
	 * @param other anything, including null (which is never the same content)
	 */
	public boolean sameContentAs(final BankSnapshot other)
	{
		if (other == null)
		{
			return false;
		}

		if (this == other)
		{
			return true;
		}

		return accountHash == other.accountHash
			&& currencyGp == other.currencyGp
			&& carriedGp == other.carriedGp
			&& Objects.equals(profileType, other.profileType)
			&& Objects.equals(items, other.items)
			// Y2: what the player carries is content too, or a Refresh that found a different inventory would leave
			// the file saying the old one - and the next launch would draw a bank nobody has.
			&& Objects.equals(inventory, other.inventory)
			&& Objects.equals(worn, other.worn);
	}

	/**
	 * Makes a snapshot that came off disk safe to read (contract C5): drops null entries and anything with a
	 * non-positive id or quantity - which is also how a bank PLACEHOLDER (quantity 0) disappears, exactly as
	 * design D6 asks - replaces a null name with "" so no renderer has to null-check, and fills in a missing
	 * profile type with {@link #DEFAULT_PROFILE_TYPE}.
	 *
	 * <p>Void, like {@code BeamLayer.normalize()}: it repairs this object in place and is called once, right
	 * after the file is parsed.
	 */
	public void normalize()
	{
		items = cleaned(items);
		// Y2: a file written before addendum Y has neither list, and Gson leaves an absent field at whatever the
		// no-arg constructor set - or at null when it instantiates without one. Both read as "carrying nothing",
		// which is the truth about a capture made before the plugin ever looked.
		inventory = cleaned(inventory);
		worn = cleaned(worn);

		if (profileType == null || profileType.isEmpty())
		{
			profileType = DEFAULT_PROFILE_TYPE;
		}

		if (capturedAtMillis < 0)
		{
			capturedAtMillis = 0;
		}

		// A hand-edited or truncated file could carry a negative worth, and a negative would be SUBTRACTED from
		// the bank's total. Absent reads as 0 through Gson already; this is the other half of that.
		if (currencyGp < 0)
		{
			currencyGp = 0;
		}

		if (carriedGp < 0)
		{
			carriedGp = 0;
		}

		if (carriedAtMillis < 0)
		{
			carriedAtMillis = 0;
		}
	}

	/**
	 * One stack list made safe: null entries and anything with a non-positive id or quantity dropped - which is also
	 * how a bank PLACEHOLDER (quantity 0) disappears, exactly as design D6 asks - and a null name replaced with ""
	 * so no renderer has to null-check. A null list is an empty one.
	 *
	 * <p>The one rule, so the bank's stacks and the carried stacks of Y2 cannot be cleaned two different ways.
	 */
	private static List<BankItem> cleaned(final List<BankItem> stacks)
	{
		final List<BankItem> clean = new ArrayList<>();
		if (stacks != null)
		{
			for (final BankItem item : stacks)
			{
				if (item == null || item.id <= 0 || item.quantity <= 0)
				{
					continue;
				}

				if (item.name == null)
				{
					item.name = "";
				}

				clean.add(item);
			}
		}
		return clean;
	}

	/**
	 * The same content captured at the same MOMENT: {@link #sameContentAs} plus {@link #capturedAtMillis}, which
	 * is the one field the content comparison leaves out (a persisted snapshot has to be told apart from a re-read
	 * of it). Spelling it this way keeps the field list in one place, so a field added to the content can never be
	 * forgotten here.
	 */
	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof BankSnapshot))
		{
			return false;
		}

		final BankSnapshot other = (BankSnapshot) o;
		return capturedAtMillis == other.capturedAtMillis && sameContentAs(other);
	}

	@Override
	public int hashCode()
	{
		int result = items == null ? 0 : items.hashCode();
		result = 31 * result + (int) (capturedAtMillis ^ (capturedAtMillis >>> 32));
		result = 31 * result + (int) (accountHash ^ (accountHash >>> 32));
		result = 31 * result + (profileType == null ? 0 : profileType.hashCode());
		result = 31 * result + (int) (currencyGp ^ (currencyGp >>> 32));
		result = 31 * result + (inventory == null ? 0 : inventory.hashCode());
		result = 31 * result + (worn == null ? 0 : worn.hashCode());
		result = 31 * result + (int) (carriedGp ^ (carriedGp >>> 32));
		return result;
	}

	/**
	 * The carried part is printed only when there IS one, so a bank captured before addendum Y - and every bank of a
	 * player with the switch off - reads exactly as it did.
	 */
	@Override
	public String toString()
	{
		final int held = inventory == null ? 0 : inventory.size();
		final int equipped = worn == null ? 0 : worn.size();
		return "BankSnapshot{items=" + (items == null ? 0 : items.size())
			+ ", capturedAtMillis=" + capturedAtMillis
			+ ", accountHash=" + accountHash
			+ ", profileType='" + profileType + "'"
			+ ", currencyGp=" + currencyGp
			+ (held == 0 && equipped == 0 && carriedGp == 0L
				? ""
				: ", inventory=" + held + ", worn=" + equipped + ", carriedGp=" + carriedGp) + '}';
	}
}
